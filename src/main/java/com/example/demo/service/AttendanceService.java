package com.example.demo.service;

import com.example.demo.attendance.AttendanceClock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.AttendanceRecord;
import com.example.demo.entity.AttendanceBreak;
import com.example.demo.entity.AttendanceEvent;
import com.example.demo.entity.User;
import com.example.demo.attendance.AttendanceEventType;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.repository.AttendanceBreakRepository;
import com.example.demo.repository.AttendanceEventRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.UserRepository;

@Service
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRepository;
    private final UserRepository userRepository;
    private final AttendancePolicyService policyService;
    private final AttendanceBreakRepository breakRepository;
    private final AttendanceEventRepository eventRepository;

    public AttendanceService(AttendanceRecordRepository attendanceRepository,
                             UserRepository userRepository,
                             AttendancePolicyService policyService,
                             AttendanceBreakRepository breakRepository,
                             AttendanceEventRepository eventRepository) {
        this.attendanceRepository = attendanceRepository;
        this.userRepository = userRepository;
        this.policyService = policyService;
        this.breakRepository = breakRepository;
        this.eventRepository = eventRepository;
    }

    public LocalDate today() {
        return AttendanceClock.today();
    }

    @Transactional(readOnly = true)
    public Optional<User> findActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> user.isActive());
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceRecord> findTodayRecord(Long userId) {
        return attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(userId)
                .or(() -> attendanceRepository.findByUser_IdAndWorkDate(userId, today())
                        .filter(value -> !value.isDeleted()));
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceRecord> findRecord(Long userId, LocalDate workDate) {
        return attendanceRepository.findByUser_IdAndWorkDate(userId, workDate).filter(value -> !value.isDeleted());
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecord> findOwnRecentRecords(Long userId) {
        return attendanceRepository.findTop30ByUser_IdOrderByWorkDateDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecord> findRecordsForDate(LocalDate workDate) {
        return attendanceRepository.findByWorkDateOrderByUser_IdAsc(workDate);
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceRecord> findOpenRecord(Long userId) {
        return attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceBreak> findOpenBreak(Long recordId) {
        return breakRepository.findFirstByAttendanceRecord_IdAndEndedAtIsNullOrderByStartedAtDesc(recordId);
    }

    /** 出勤の勤務日を予定から決定し、打刻履歴を追記する。 */
    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "CLOCK_IN", target = "ATTENDANCE")
    public AttendanceRecord clockIn(Long userId) {
        User user = lockActiveUser(userId);
        LocalDateTime now = AttendanceClock.now();
        if (findOpenRecord(userId).isPresent()) {
            throw new IllegalStateException("未退勤の勤務記録があります。先に退勤または修正申請をしてください。");
        }
        LocalDate workDate = determineWorkDate(userId, now);
        ScheduleSnapshot schedule = policyService.resolve(userId, workDate);
        if (schedule.dayType() != DayType.WORKDAY) {
            throw new IllegalStateException("勤務予定がありません。休日出勤の申請・承認を確認してください。");
        }

        if (attendanceRepository.findByUser_IdAndWorkDate(userId, workDate).isPresent()) {
            throw new IllegalStateException("本日はすでに出勤打刻済みです。");
        }

        AttendanceRecord record = new AttendanceRecord();
        record.setUser(user);
        record.setWorkDate(workDate);
        record.setClockInAt(now);
        attendanceRepository.saveAndFlush(record);
        event(record, user, user, AttendanceEventType.CLOCK_IN, now, schedule.workMode(), null);
        return record;
    }

    /** 退勤は日付が変わっても未退勤の勤務に対応させる。 */
    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "CLOCK_OUT", target = "ATTENDANCE")
    public AttendanceRecord clockOut(Long userId) {
        lockActiveUser(userId);
        LocalDateTime now = AttendanceClock.now();

        AttendanceRecord record = attendanceRepository
                .findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(userId)
                .orElseThrow(() -> new IllegalStateException("未退勤の出勤打刻がありません。"));

        if (record.getClockOutAt() != null) {
            throw new IllegalStateException("本日はすでに退勤打刻済みです。");
        }

        if (findOpenBreak(record.getId()).isPresent()) {
            throw new IllegalStateException("休憩を終了してから退勤してください。");
        }
        requireChronologicalPunch(record, now);
        if (!now.isAfter(record.getClockInAt())) {
            throw new IllegalStateException("退勤時刻は出勤時刻より後である必要があります。勤務記録を確認してください。");
        }
        record.setClockOutAt(now);
        attendanceRepository.saveAndFlush(record);
        event(record, record.getUser(), record.getUser(), AttendanceEventType.CLOCK_OUT,
                now, policyService.resolve(userId, record.getWorkDate()).workMode(), null);
        return record;
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "BREAK_START", target = "ATTENDANCE")
    public AttendanceRecord startBreak(Long userId) {
        lockActiveUser(userId);
        AttendanceRecord record = findOpenRecord(userId)
                .orElseThrow(() -> new IllegalStateException("出勤打刻がありません。"));
        if (findOpenBreak(record.getId()).isPresent()) {
            throw new IllegalStateException("すでに休憩中です。");
        }
        LocalDateTime now = AttendanceClock.now();
        requireChronologicalPunch(record, now);
        AttendanceBreak value = new AttendanceBreak();
        value.setAttendanceRecord(record);
        value.setStartedAt(now);
        breakRepository.save(value);
        event(record, record.getUser(), record.getUser(), AttendanceEventType.BREAK_START,
                now, null, null);
        return record;
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "BREAK_END", target = "ATTENDANCE")
    public AttendanceRecord endBreak(Long userId) {
        lockActiveUser(userId);
        AttendanceRecord record = findOpenRecord(userId)
                .orElseThrow(() -> new IllegalStateException("出勤打刻がありません。"));
        AttendanceBreak value = findOpenBreak(record.getId())
                .orElseThrow(() -> new IllegalStateException("開始済みの休憩がありません。"));
        LocalDateTime now = AttendanceClock.now();
        requireChronologicalPunch(record, now);
        value.setEndedAt(now);
        breakRepository.save(value);
        event(record, record.getUser(), record.getUser(), AttendanceEventType.BREAK_END,
                now, null, null);
        return record;
    }

    /** 補正済み時刻や時計の後退があっても、既存記録より前に実打刻を追記しない。 */
    private void requireChronologicalPunch(AttendanceRecord record, LocalDateTime now) {
        if (record.getClockInAt() == null || now.isBefore(record.getClockInAt())) {
            throw new IllegalStateException("現在時刻が出勤記録より前です。勤務記録を確認してから操作してください。");
        }
        for (AttendanceBreak rest : breakRepository.findByAttendanceRecord_IdOrderByStartedAtAsc(record.getId())) {
            if (now.isBefore(rest.getStartedAt())
                    || (rest.getEndedAt() != null && now.isBefore(rest.getEndedAt()))) {
                throw new IllegalStateException("現在時刻が休憩記録より前です。勤務記録を確認してから操作してください。");
            }
        }
    }

    /** 出退勤・休憩の状態遷移は社員単位で直列化する。別会話の同時打刻も対象。 */
    private User lockActiveUser(Long userId) {
        return userRepository.findForUpdate(userId).filter(User::isActive)
                .orElseThrow(() -> new IllegalStateException("有効なユーザーではありません。"));
    }

    LocalDate determineWorkDate(Long userId, LocalDateTime now) {
        LocalDate previous = now.toLocalDate().minusDays(1);
        ScheduleSnapshot prior = policyService.resolve(userId, previous);
        if (prior.dayType() == DayType.WORKDAY && prior.plannedEndAt() != null
                && prior.plannedEndAt().toLocalDate().isAfter(previous)
                && !now.isAfter(prior.plannedEndAt())
                && prior.plannedStartAt() != null
                && !now.isBefore(prior.plannedStartAt())) {
            return previous;
        }
        return now.toLocalDate();
    }

    private void event(AttendanceRecord record, User subject, User actor,
                       AttendanceEventType type, LocalDateTime at,
                       WorkMode mode, String note) {
        AttendanceEvent value = new AttendanceEvent();
        value.setAttendanceRecord(record);
        value.setUser(subject);
        value.setActor(actor);
        value.setEventType(type);
        value.setEventAt(at);
        value.setWorkMode(mode);
        value.setNote(note);
        eventRepository.save(value);
    }
}
