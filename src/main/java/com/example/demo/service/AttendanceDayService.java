package com.example.demo.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.example.demo.attendance.AttendanceDayView;
import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.RequestStatus;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.AttendanceBreak;
import com.example.demo.entity.AttendanceRecord;
import com.example.demo.entity.User;
import com.example.demo.repository.AttendanceBreakRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.AttendanceRequestRepository;
import com.example.demo.repository.UserRepository;

@Service
public class AttendanceDayService {
    private static final ZoneId WORK_ZONE = ZoneId.of("Asia/Tokyo");
    private final AttendancePolicyService policyService;
    private final AttendanceRecordRepository recordRepository;
    private final AttendanceBreakRepository breakRepository;
    private final AttendanceRequestRepository requestRepository;
    private final UserRepository userRepository;

    public AttendanceDayService(AttendancePolicyService policyService,
                                AttendanceRecordRepository recordRepository,
                                AttendanceBreakRepository breakRepository,
                                AttendanceRequestRepository requestRepository,
                                UserRepository userRepository) {
        this.policyService = policyService;
        this.recordRepository = recordRepository;
        this.breakRepository = breakRepository;
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public AttendanceDayView evaluate(User user, LocalDate date) {
        boolean outsideEmployment = (user.getCreatedAt() != null
                && date.isBefore(user.getCreatedAt().toLocalDate()))
                || (user.getDeletedAt() != null
                    && date.isAfter(user.getDeletedAt().toLocalDate()));
        if (outsideEmployment
                && recordRepository.findByUser_IdAndWorkDate(user.getId(), date).isEmpty()) {
            ScheduleSnapshot empty = new ScheduleSnapshot(date, DayType.REST_DAY,
                    WorkMode.OFFICE, null, null, 0, false);
            return new AttendanceDayView(user.getId(), user.getRealName(), date,
                    empty, null, null, 0, 0, List.of("在籍外"));
        }
        ScheduleSnapshot schedule = policyService.resolve(user.getId(), date);
        AttendanceRecord record = recordRepository.findByUser_IdAndWorkDate(user.getId(), date)
                .orElse(null);
        LocalDateTime now = LocalDateTime.now(WORK_ZONE);
        var policy = policyService.policyForDate(date);
        List<String> states = new ArrayList<>();

        if (schedule.dayType() == DayType.LEAVE) states.add("休暇");
        if (schedule.dayType() == DayType.HOLIDAY) states.add("会社休日");
        if (schedule.dayType() == DayType.REST_DAY) states.add("所定休日");
        if (schedule.workMode() == WorkMode.FIELD && schedule.dayType() == DayType.WORKDAY) states.add("外勤");
        if (schedule.workMode() == WorkMode.REMOTE && schedule.dayType() == DayType.WORKDAY) states.add("在宅勤務");

        long breakMinutes = 0;
        long workedMinutes = 0;
        if (record == null) {
            if (schedule.dayType() == DayType.WORKDAY && schedule.plannedStartAt() != null) {
                if (now.isAfter(schedule.plannedEndAt())) states.add("欠勤要確認");
                else if (now.isAfter(schedule.plannedStartAt().plusMinutes(policy.lateGraceMinutes())))
                    states.add("出勤未打刻");
                else states.add("勤務予定");
            }
        } else {
            if (schedule.dayType() != DayType.WORKDAY) states.add("休日打刻要確認");
            if (schedule.plannedStartAt() != null && record.getClockInAt().isAfter(
                    schedule.plannedStartAt().plusMinutes(policy.lateGraceMinutes()))) {
                states.add("遅刻");
            }
            List<AttendanceBreak> breaks = breakRepository
                    .findByAttendanceRecord_IdOrderByStartedAtAsc(record.getId());
            for (AttendanceBreak item : breaks) {
                LocalDateTime end = item.getEndedAt() == null ? now : item.getEndedAt();
                breakMinutes += Math.max(0, Duration.between(item.getStartedAt(), end).toMinutes());
            }
            if (record.getClockOutAt() == null) {
                if (breaks.stream().anyMatch(item -> item.getEndedAt() == null)) states.add("休憩中");
                else if (schedule.plannedEndAt() != null
                        && now.isAfter(schedule.plannedEndAt().plusMinutes(policy.earlyGraceMinutes())))
                    states.add("退勤未打刻");
                else states.add("勤務中");
            } else {
                workedMinutes = Math.max(0, Duration.between(
                        record.getClockInAt(), record.getClockOutAt()).toMinutes() - breakMinutes);
                states.add("退勤済み");
                if (schedule.plannedEndAt() != null && record.getClockOutAt().isBefore(
                        schedule.plannedEndAt().minusMinutes(policy.earlyGraceMinutes()))) {
                    states.add("早退");
                }
                if (schedule.plannedEndAt() != null && record.getClockOutAt().isAfter(
                        schedule.plannedEndAt().plusMinutes(policy.overtimeThresholdMinutes()))) {
                    boolean approved = requestRepository
                        .findByUser_IdAndRequestTypeAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            user.getId(), AttendanceRequestType.OVERTIME,
                            RequestStatus.APPROVED, date, date).stream()
                        .anyMatch(request -> request.getRequestedOvertimeEndAt() != null
                            && !record.getClockOutAt().isAfter(request.getRequestedOvertimeEndAt()));
                    states.add(approved ? "承認済み残業" : "残業要申請");
                }
                if (schedule.dayType() == DayType.WORKDAY && breakMinutes < schedule.breakMinutes()) {
                    states.add("休憩不足要確認");
                }
            }
        }
        if (states.isEmpty()) states.add("記録なし");
        return new AttendanceDayView(user.getId(), user.getRealName(), date, schedule,
                record == null ? null : record.getClockInAt(),
                record == null ? null : record.getClockOutAt(), breakMinutes,
                workedMinutes, states);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceDayView> allActiveForDate(LocalDate date, int page) {
        return userRepository.findByStatusOrderByIdAsc(1, PageRequest.of(Math.max(0, page), 50))
                .map(user -> evaluate(user, date));
    }
}
