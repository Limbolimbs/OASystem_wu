package com.example.demo.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.attendance.DayType;
import com.example.demo.attendance.AttendanceClock;
import com.example.demo.attendance.AttendanceRules;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.AttendanceAuditLog;
import com.example.demo.entity.AttendancePolicy;
import com.example.demo.entity.AttendancePolicyHistory;
import com.example.demo.entity.CompanyHoliday;
import com.example.demo.entity.User;
import com.example.demo.entity.WorkSchedule;
import com.example.demo.repository.AttendanceAuditLogRepository;
import com.example.demo.repository.AttendancePolicyRepository;
import com.example.demo.repository.AttendancePolicyHistoryRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.CompanyHolidayRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WorkScheduleRepository;

@Service
public class AttendancePolicyService {

    private final AttendancePolicyRepository policyRepository;
    private final AttendancePolicyHistoryRepository historyRepository;
    private final CompanyHolidayRepository holidayRepository;
    private final WorkScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final AttendanceAuditLogRepository auditRepository;
    private final AttendanceRecordRepository recordRepository;

    public AttendancePolicyService(
            AttendancePolicyRepository policyRepository,
            AttendancePolicyHistoryRepository historyRepository,
            CompanyHolidayRepository holidayRepository,
            WorkScheduleRepository scheduleRepository,
            UserRepository userRepository,
            AttendanceAuditLogRepository auditRepository,
            AttendanceRecordRepository recordRepository) {
        this.policyRepository = policyRepository;
        this.historyRepository = historyRepository;
        this.holidayRepository = holidayRepository;
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.recordRepository = recordRepository;
    }

    @Transactional(readOnly = true)
    public AttendancePolicy currentPolicy() {
        return policyRepository.findById(1L).orElseGet(AttendancePolicy::new);
    }

    @Transactional(readOnly = true)
    public AttendanceRules policyForDate(LocalDate date) {
        return historyRepository.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(date)
                .map(value -> new AttendanceRules(value.getStandardStart(), value.getStandardEnd(),
                        value.getBreakMinutes(), value.getLateGraceMinutes(),
                        value.getEarlyGraceMinutes(), value.getOvertimeThresholdMinutes(),
                        value.getWorkDaysMask()))
                .orElseGet(() -> {
                    AttendancePolicy value = currentPolicy();
                    return new AttendanceRules(value.getStandardStart(), value.getStandardEnd(),
                            value.getBreakMinutes(), value.getLateGraceMinutes(),
                            value.getEarlyGraceMinutes(), value.getOvertimeThresholdMinutes(),
                            value.getWorkDaysMask());
                });
    }

    @Transactional(readOnly = true)
    public List<CompanyHoliday> holidays() {
        return holidayRepository.findAllByOrderByHolidayDateAsc();
    }

    @Transactional(readOnly = true)
    public List<WorkSchedule> schedulesForDate(LocalDate date) {
        return scheduleRepository.findByWorkDateOrderByUser_IdAsc(date);
    }

    @Transactional(readOnly = true)
    public ScheduleSnapshot resolve(Long userId, LocalDate date) {
        Optional<WorkSchedule> assigned = scheduleRepository.findByUser_IdAndWorkDate(userId, date);
        if (assigned.isPresent()) {
            WorkSchedule value = assigned.get();
            return new ScheduleSnapshot(date, value.getDayType(), value.getWorkMode(),
                    value.getPlannedStartAt(), value.getPlannedEndAt(),
                    value.getBreakMinutes(), true);
        }

        AttendanceRules policy = policyForDate(date);
        if (holidayRepository.existsById(date)) {
            return new ScheduleSnapshot(date, DayType.HOLIDAY, WorkMode.OFFICE,
                    null, null, 0, false);
        }

        int weekdayBit = 1 << (date.getDayOfWeek().getValue() - 1);
        if ((policy.workDaysMask() & weekdayBit) == 0) {
            return new ScheduleSnapshot(date, DayType.REST_DAY, WorkMode.OFFICE,
                    null, null, 0, false);
        }

        LocalDateTime start = date.atTime(policy.standardStart());
        LocalDateTime end = date.atTime(policy.standardEnd());
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }
        return new ScheduleSnapshot(date, DayType.WORKDAY, WorkMode.OFFICE,
                start, end, policy.breakMinutes(), false);
    }

    @Transactional
    public AttendancePolicy updatePolicy(
            Long actorId,
            LocalTime start,
            LocalTime end,
            int breakMinutes,
            int lateGraceMinutes,
            int earlyGraceMinutes,
            int overtimeThresholdMinutes,
            int workDaysMask) {

        if (start == null || end == null || start.equals(end)
                || breakMinutes < 0 || breakMinutes > 720
                || lateGraceMinutes < 0 || lateGraceMinutes > 120
                || earlyGraceMinutes < 0 || earlyGraceMinutes > 120
                || overtimeThresholdMinutes < 0 || overtimeThresholdMinutes > 180
                || workDaysMask < 1 || workDaysMask > 127) {
            throw new IllegalArgumentException("勤務ルールの入力値を確認してください。");
        }

        AttendancePolicy policy = policyRepository.findById(1L)
                .orElseGet(AttendancePolicy::new);
        String before = policySummary(policy);
        policy.setStandardStart(start);
        policy.setStandardEnd(end);
        policy.setBreakMinutes(breakMinutes);
        policy.setLateGraceMinutes(lateGraceMinutes);
        policy.setEarlyGraceMinutes(earlyGraceMinutes);
        policy.setOvertimeThresholdMinutes(overtimeThresholdMinutes);
        policy.setWorkDaysMask(workDaysMask);
        AttendancePolicy saved = policyRepository.save(policy);
        LocalDate effective = AttendanceClock.today().plusDays(1);
        AttendancePolicyHistory next = historyRepository.findById(effective)
                .orElseGet(AttendancePolicyHistory::new);
        next.setEffectiveFrom(effective);
        next.setStandardStart(start);
        next.setStandardEnd(end);
        next.setBreakMinutes(breakMinutes);
        next.setLateGraceMinutes(lateGraceMinutes);
        next.setEarlyGraceMinutes(earlyGraceMinutes);
        next.setOvertimeThresholdMinutes(overtimeThresholdMinutes);
        next.setWorkDaysMask(workDaysMask);
        historyRepository.save(next);
        audit(actorId, "UPDATE_POLICY", "attendance_policy", "1", before, policySummary(saved));
        return saved;
    }

    @Transactional
    public WorkSchedule assignSchedule(
            Long actorId, Long userId, LocalDate workDate,
            DayType dayType, WorkMode workMode,
            LocalDateTime plannedStartAt, LocalDateTime plannedEndAt, int breakMinutes) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("対象ユーザーが見つかりません。"));
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new IllegalStateException("無効な従業員に勤務予定を登録できません。");
        }
        if (workDate == null || dayType == null || workMode == null
                || breakMinutes < 0 || breakMinutes > 720) {
            throw new IllegalArgumentException("勤務予定の入力値を確認してください。");
        }

        if (dayType == DayType.WORKDAY) {
            if (plannedStartAt == null || plannedEndAt == null
                    || !plannedStartAt.toLocalDate().equals(workDate)
                    || !plannedEndAt.isAfter(plannedStartAt)
                    || plannedEndAt.isAfter(plannedStartAt.plusHours(36))) {
                throw new IllegalArgumentException("勤務開始・終了日時を確認してください。");
            }
        } else {
            if (recordRepository.findByUser_IdAndWorkDate(userId, workDate).isPresent()) {
                throw new IllegalStateException("打刻済みの日は非勤務日に変更できません。先に記録を確認してください。");
            }
            plannedStartAt = null;
            plannedEndAt = null;
            breakMinutes = 0;
        }

        WorkSchedule schedule = scheduleRepository.findByUser_IdAndWorkDate(userId, workDate)
                .orElseGet(WorkSchedule::new);
        String before = schedule.getId() == null ? null : scheduleSummary(schedule);
        schedule.setUser(user);
        schedule.setWorkDate(workDate);
        schedule.setDayType(dayType);
        schedule.setWorkMode(workMode);
        schedule.setPlannedStartAt(plannedStartAt);
        schedule.setPlannedEndAt(plannedEndAt);
        schedule.setBreakMinutes(breakMinutes);
        WorkSchedule saved = scheduleRepository.save(schedule);
        audit(actorId, "UPSERT_SCHEDULE", "work_schedule", userId + ":" + workDate,
                before, scheduleSummary(saved));
        return saved;
    }

    @Transactional
    public void saveHoliday(Long actorId, LocalDate date, String name) {
        if (date == null || name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("休日の日付と名称を入力してください。");
        }
        CompanyHoliday holiday = holidayRepository.findById(date)
                .orElseGet(CompanyHoliday::new);
        String before = holiday.getName();
        holiday.setHolidayDate(date);
        holiday.setName(name.trim());
        holidayRepository.save(holiday);
        audit(actorId, "UPSERT_HOLIDAY", "company_holiday", date.toString(),
                before, holiday.getName());
    }

    @Transactional
    public void removeHoliday(Long actorId, LocalDate date) {
        CompanyHoliday holiday = holidayRepository.findById(date)
                .orElseThrow(() -> new IllegalArgumentException("対象の休日が見つかりません。"));
        audit(actorId, "DELETE_HOLIDAY", "company_holiday", date.toString(),
                holiday.getName(), null);
        holidayRepository.delete(holiday);
    }

    @Transactional
    public void removeSchedule(Long actorId, Long scheduleId) {
        WorkSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("対象の勤務予定が見つかりません。"));
        audit(actorId, "DELETE_SCHEDULE", "work_schedule",
                schedule.getUser().getId() + ":" + schedule.getWorkDate(),
                scheduleSummary(schedule), null);
        scheduleRepository.delete(schedule);
    }

    private void audit(Long actorId, String action, String targetType, String targetId,
                       String before, String after) {
        AttendanceAuditLog log = new AttendanceAuditLog();
        log.setActor(userRepository.getReferenceById(actorId));
        log.setActionName(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setOldValue(before);
        log.setNewValue(after);
        auditRepository.save(log);
    }

    private String policySummary(AttendancePolicy policy) {
        return policy.getStandardStart() + "|" + policy.getStandardEnd() + "|"
                + policy.getBreakMinutes() + "|" + policy.getLateGraceMinutes() + "|"
                + policy.getEarlyGraceMinutes() + "|" + policy.getOvertimeThresholdMinutes()
                + "|" + policy.getWorkDaysMask();
    }

    private String scheduleSummary(WorkSchedule schedule) {
        return schedule.getDayType() + "|" + schedule.getWorkMode() + "|"
                + schedule.getPlannedStartAt() + "|" + schedule.getPlannedEndAt()
                + "|" + schedule.getBreakMinutes();
    }
}
