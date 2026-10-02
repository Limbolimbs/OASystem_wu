package com.example.demo.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private static final ZoneId WORK_ZONE = ZoneId.of("Asia/Tokyo");

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
        return ZonedDateTime.now(WORK_ZONE).toLocalDate();
    }

    @Transactional(readOnly = true)
    public Optional<User> findActiveUser(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()));
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceRecord> findTodayRecord(Long userId) {
        return attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(userId)
                .or(() -> attendanceRepository.findByUser_IdAndWorkDate(userId, today()));
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceRecord> findRecord(Long userId, LocalDate workDate) {
        return attendanceRepository.findByUser_IdAndWorkDate(userId, workDate);
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
    public void clockIn(Long userId) {
        User user = findActiveUser(userId)
                .orElseThrow(() -> new IllegalStateException("有効なユーザーではありません。"));
        LocalDateTime now = ZonedDateTime.now(WORK_ZONE).toLocalDateTime();
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
    }

    /** 退勤は日付が変わっても未退勤の勤務に対応させる。 */
    @Transactional
    public void clockOut(Long userId) {
        findActiveUser(userId)
                .orElseThrow(() -> new IllegalStateException("有効なユーザーではありません。"));
        LocalDateTime now = ZonedDateTime.now(WORK_ZONE).toLocalDateTime();

        AttendanceRecord record = attendanceRepository
                .findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(userId)
                .orElseThrow(() -> new IllegalStateException("未退勤の出勤打刻がありません。"));

        if (record.getClockOutAt() != null) {
            throw new IllegalStateException("本日はすでに退勤打刻済みです。");
        }

        if (findOpenBreak(record.getId()).isPresent()) {
            throw new IllegalStateException("休憩を終了してから退勤してください。");
        }
        record.setClockOutAt(now);
        attendanceRepository.saveAndFlush(record);
        event(record, record.getUser(), record.getUser(), AttendanceEventType.CLOCK_OUT,
                now, policyService.resolve(userId, record.getWorkDate()).workMode(), null);
    }

    @Transactional
    public void startBreak(Long userId) {
        AttendanceRecord record = findOpenRecord(userId)
                .orElseThrow(() -> new IllegalStateException("出勤打刻がありません。"));
        if (findOpenBreak(record.getId()).isPresent()) {
            throw new IllegalStateException("すでに休憩中です。");
        }
        LocalDateTime now = LocalDateTime.now(WORK_ZONE);
        AttendanceBreak value = new AttendanceBreak();
        value.setAttendanceRecord(record);
        value.setStartedAt(now);
        breakRepository.save(value);
        event(record, record.getUser(), record.getUser(), AttendanceEventType.BREAK_START,
                now, null, null);
    }

    @Transactional
    public void endBreak(Long userId) {
        AttendanceRecord record = findOpenRecord(userId)
                .orElseThrow(() -> new IllegalStateException("出勤打刻がありません。"));
        AttendanceBreak value = findOpenBreak(record.getId())
                .orElseThrow(() -> new IllegalStateException("開始済みの休憩がありません。"));
        LocalDateTime now = LocalDateTime.now(WORK_ZONE);
        value.setEndedAt(now);
        breakRepository.save(value);
        event(record, record.getUser(), record.getUser(), AttendanceEventType.BREAK_END,
                now, null, null);
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
