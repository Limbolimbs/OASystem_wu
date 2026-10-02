package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.demo.attendance.AttendanceClock;
import com.example.demo.attendance.DayType;
import com.example.demo.entity.AttendancePolicy;
import com.example.demo.entity.AttendancePolicyHistory;
import com.example.demo.entity.User;
import com.example.demo.repository.AttendanceAuditLogRepository;
import com.example.demo.repository.AttendancePolicyHistoryRepository;
import com.example.demo.repository.AttendancePolicyRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.CompanyHolidayRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WorkScheduleRepository;

@ExtendWith(MockitoExtension.class)
class AttendancePolicyServiceTest {
    @Mock AttendancePolicyRepository policies;
    @Mock AttendancePolicyHistoryRepository history;
    @Mock CompanyHolidayRepository holidays;
    @Mock WorkScheduleRepository schedules;
    @Mock UserRepository users;
    @Mock AttendanceAuditLogRepository audits;
    @Mock AttendanceRecordRepository records;
    @InjectMocks AttendancePolicyService service;

    @Test
    void pastDateUsesHistoricalRule() {
        LocalDate date = LocalDate.of(2026, 9, 1);
        AttendancePolicyHistory old = new AttendancePolicyHistory();
        old.setStandardStart(LocalTime.of(8, 30));
        old.setStandardEnd(LocalTime.of(17, 30));
        old.setBreakMinutes(45);
        old.setWorkDaysMask(31);
        when(history.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(date))
                .thenReturn(Optional.of(old));

        var schedule = service.resolve(1L, date);

        assertEquals(DayType.WORKDAY, schedule.dayType());
        assertEquals(date.atTime(8, 30), schedule.plannedStartAt());
        assertEquals(45, schedule.breakMinutes());
    }

    @Test
    void changedRuleTakesEffectTomorrow() {
        AttendancePolicy policy = new AttendancePolicy();
        when(policies.findById(1L)).thenReturn(Optional.of(policy));
        when(policies.save(policy)).thenReturn(policy);
        when(history.findById(AttendanceClock.today().plusDays(1))).thenReturn(Optional.empty());
        when(users.getReferenceById(1L)).thenReturn(new User());

        service.updatePolicy(1L, LocalTime.of(10, 0), LocalTime.of(19, 0),
                60, 5, 5, 15, 31);

        ArgumentCaptor<AttendancePolicyHistory> saved =
                ArgumentCaptor.forClass(AttendancePolicyHistory.class);
        verify(history).save(saved.capture());
        assertEquals(AttendanceClock.today().plusDays(1), saved.getValue().getEffectiveFrom());
        assertEquals(LocalTime.of(10, 0), saved.getValue().getStandardStart());
    }
}
