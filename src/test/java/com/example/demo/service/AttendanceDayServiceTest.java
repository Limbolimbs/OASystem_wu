package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.AttendanceRules;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.RequestStatus;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.AttendanceRecord;
import com.example.demo.entity.AttendanceRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.AttendanceBreakRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.AttendanceRequestRepository;
import com.example.demo.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AttendanceDayServiceTest {
    @Mock AttendancePolicyService policies;
    @Mock AttendanceRecordRepository records;
    @Mock AttendanceBreakRepository breaks;
    @Mock AttendanceRequestRepository requests;
    @Mock UserRepository users;
    @InjectMocks AttendanceDayService service;
    private User user;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setRealName("山田太郎");
        date = LocalDate.now(ZoneId.of("Asia/Tokyo")).minusDays(2);
    }

    @Test
    void absentDayRequiresReviewRatherThanAutomaticPenalty() {
        when(policies.resolve(1L, date)).thenReturn(schedule());
        when(policies.policyForDate(date)).thenReturn(rules());
        when(records.findByUser_IdAndWorkDate(1L, date)).thenReturn(Optional.empty());
        assertTrue(service.evaluate(user, date).getStatuses().contains("欠勤要確認"));
    }

    @Test
    void lateAndEarlyAreDetectedFromScheduledTimes() {
        AttendanceRecord record = record(date.atTime(9, 5), date.atTime(17, 30));
        when(policies.resolve(1L, date)).thenReturn(schedule());
        when(policies.policyForDate(date)).thenReturn(rules());
        when(records.findByUser_IdAndWorkDate(1L, date)).thenReturn(Optional.of(record));
        when(breaks.findByAttendanceRecord_IdOrderByStartedAtAsc(null)).thenReturn(List.of());
        var states = service.evaluate(user, date).getStatuses();
        assertTrue(states.contains("遅刻"));
        assertTrue(states.contains("早退"));
    }

    @Test
    void overtimeBeyondApprovedEndStillNeedsRequest() {
        AttendanceRecord record = record(date.atTime(9, 0), date.atTime(20, 0));
        AttendanceRequest approved = new AttendanceRequest();
        approved.setRequestedOvertimeEndAt(date.atTime(19, 0));
        when(policies.resolve(1L, date)).thenReturn(schedule());
        when(policies.policyForDate(date)).thenReturn(rules());
        when(records.findByUser_IdAndWorkDate(1L, date)).thenReturn(Optional.of(record));
        when(breaks.findByAttendanceRecord_IdOrderByStartedAtAsc(null)).thenReturn(List.of());
        when(requests.findByUser_IdAndRequestTypeAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                1L, AttendanceRequestType.OVERTIME, RequestStatus.APPROVED, date, date))
                .thenReturn(List.of(approved));
        assertTrue(service.evaluate(user, date).getStatuses().contains("残業要申請"));
    }

    @Test
    void datesAfterDeactivationAreNotCountedAsAbsence() {
        user.setStatus(0);
        user.setDeletedAt(date.minusDays(1).atTime(12, 0));
        when(records.findByUser_IdAndWorkDate(1L, date)).thenReturn(Optional.empty());
        assertTrue(service.evaluate(user, date).getStatuses().contains("在籍外"));
    }

    private ScheduleSnapshot schedule() {
        return new ScheduleSnapshot(date, DayType.WORKDAY, WorkMode.OFFICE,
                date.atTime(9, 0), date.atTime(18, 0), 60, false);
    }

    private AttendanceRules rules() {
        return new AttendanceRules(java.time.LocalTime.of(9, 0),
                java.time.LocalTime.of(18, 0), 60, 0, 0, 0, 31);
    }

    private AttendanceRecord record(java.time.LocalDateTime in, java.time.LocalDateTime out) {
        AttendanceRecord value = new AttendanceRecord();
        value.setUser(user);
        value.setWorkDate(date);
        value.setClockInAt(in);
        value.setClockOutAt(out);
        return value;
    }
}
