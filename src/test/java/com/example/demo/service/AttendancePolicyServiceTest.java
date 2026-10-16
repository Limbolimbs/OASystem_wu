package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
import com.example.demo.entity.CompanyHoliday;
import com.example.demo.entity.WorkSchedule;
import com.example.demo.attendance.WorkMode;
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

    @Test void holidayDeactivationSavesOriginalRecordInsteadOfDeletingIt() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        CompanyHoliday holiday = new CompanyHoliday(); holiday.setHolidayDate(date); holiday.setName("会社休日");
        when(holidays.findById(date)).thenReturn(Optional.of(holiday));
        service.removeHoliday(1L, date);
        assertTrue(holiday.isDeleted()); assertNotNull(holiday.getDeletedAt());
        assertEquals(date, holiday.getHolidayDate());
        verify(holidays).save(holiday); verify(holidays, never()).delete(any());
    }

    @Test void reRegisteringHolidayRestoresSamePrimaryKey() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        CompanyHoliday holiday = new CompanyHoliday(); holiday.setHolidayDate(date); holiday.setName("旧休日"); holiday.softDelete();
        when(holidays.findById(date)).thenReturn(Optional.of(holiday));
        service.saveHoliday(1L, date, " 新休日 ");
        assertFalse(holiday.isDeleted()); assertEquals("新休日", holiday.getName());
        assertEquals(date, holiday.getHolidayDate()); verify(holidays).save(holiday);
    }

    @Test void scheduleDeactivationKeepsOriginalRecord() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        User user = new User(); user.setId(2L);
        WorkSchedule schedule = new WorkSchedule(); schedule.setUser(user); schedule.setWorkDate(date);
        when(schedules.findById(5L)).thenReturn(Optional.of(schedule));
        service.removeSchedule(1L, 5L);
        assertTrue(schedule.isDeleted()); assertNotNull(schedule.getDeletedAt());
        verify(schedules).save(schedule); verify(schedules, never()).delete(any());
    }

    @Test void assigningSameDayRestoresExistingScheduleRatherThanCreatingDuplicate() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        User user = new User(); user.setId(2L);
        WorkSchedule schedule = new WorkSchedule(); schedule.setUser(user); schedule.setWorkDate(date); schedule.softDelete();
        when(users.findForUpdate(2L)).thenReturn(Optional.of(user));
        when(schedules.findByUser_IdAndWorkDate(2L, date)).thenReturn(Optional.of(schedule));
        when(schedules.save(schedule)).thenReturn(schedule);
        service.assignSchedule(1L, 2L, date, DayType.WORKDAY, WorkMode.OFFICE, date.atTime(9, 0), date.atTime(18, 0), 60);
        assertFalse(schedule.isDeleted()); verify(schedules).save(schedule);
    }

    @Test void inactiveIndividualScheduleDoesNotOverrideCurrentWorkingRule() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        WorkSchedule schedule = new WorkSchedule(); schedule.setDayType(DayType.LEAVE); schedule.softDelete();
        when(schedules.findByUser_IdAndWorkDate(2L, date)).thenReturn(Optional.of(schedule));
        var resolved = service.resolve(2L, date);
        assertEquals(DayType.WORKDAY, resolved.dayType()); assertFalse(resolved.explicitlyAssigned());
    }
}
