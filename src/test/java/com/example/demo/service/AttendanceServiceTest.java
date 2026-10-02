package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.demo.attendance.DayType;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.AttendanceBreak;
import com.example.demo.entity.AttendanceEvent;
import com.example.demo.entity.AttendanceRecord;
import com.example.demo.entity.User;
import com.example.demo.repository.AttendanceBreakRepository;
import com.example.demo.repository.AttendanceEventRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {
    @Mock AttendanceRecordRepository attendanceRepository;
    @Mock UserRepository userRepository;
    @Mock AttendancePolicyService policyService;
    @Mock AttendanceBreakRepository breakRepository;
    @Mock AttendanceEventRepository eventRepository;
    @InjectMocks AttendanceService attendanceService;
    private User activeUser;

    @BeforeEach
    void setUp() {
        activeUser = new User();
        activeUser.setId(1L);
        activeUser.setStatus(1);
    }

    @Test
    void clockInCreatesRecordAndEvent() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(1L))
                .thenReturn(Optional.empty());
        when(policyService.resolve(any(), any())).thenAnswer(call -> {
            LocalDate date = call.getArgument(1);
            return new ScheduleSnapshot(date, DayType.WORKDAY, WorkMode.OFFICE,
                    date.atTime(9, 0), date.atTime(18, 0), 60, false);
        });
        when(attendanceRepository.findByUser_IdAndWorkDate(any(), any())).thenReturn(Optional.empty());

        attendanceService.clockIn(1L);

        verify(attendanceRepository).saveAndFlush(any(AttendanceRecord.class));
        verify(eventRepository).save(any(AttendanceEvent.class));
    }

    @Test
    void duplicateClockInDoesNotOverwriteOpenRecord() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(1L))
                .thenReturn(Optional.of(new AttendanceRecord()));
        assertThrows(IllegalStateException.class, () -> attendanceService.clockIn(1L));
        verify(attendanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void clockOutRequiresOpenClockIn() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(1L))
                .thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> attendanceService.clockOut(1L));
    }

    @Test
    void inactiveUserCannotClockIn() {
        activeUser.setStatus(0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        assertThrows(IllegalStateException.class, () -> attendanceService.clockIn(1L));
    }

    @Test
    void clockOutCompletesPriorDayOpenRecord() {
        AttendanceRecord record = new AttendanceRecord();
        record.setUser(activeUser);
        record.setWorkDate(LocalDate.now().minusDays(1));
        record.setClockInAt(LocalDateTime.now().minusHours(8));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(1L))
                .thenReturn(Optional.of(record));
        when(policyService.resolve(1L, record.getWorkDate())).thenReturn(new ScheduleSnapshot(
                record.getWorkDate(), DayType.WORKDAY, WorkMode.OFFICE,
                record.getClockInAt(), record.getClockInAt().plusHours(8), 60, true));

        attendanceService.clockOut(1L);

        assertNotNull(record.getClockOutAt());
        verify(attendanceRepository).saveAndFlush(record);
        verify(eventRepository).save(any(AttendanceEvent.class));
    }

    @Test
    void openBreakBlocksClockOut() {
        AttendanceRecord record = new AttendanceRecord();
        record.setUser(activeUser);
        record.setWorkDate(LocalDate.now());
        record.setClockInAt(LocalDateTime.now().minusHours(2));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser));
        when(attendanceRepository.findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(1L))
                .thenReturn(Optional.of(record));
        when(breakRepository.findFirstByAttendanceRecord_IdAndEndedAtIsNullOrderByStartedAtDesc(null))
                .thenReturn(Optional.of(new AttendanceBreak()));
        assertThrows(IllegalStateException.class, () -> attendanceService.clockOut(1L));
        verify(attendanceRepository, never()).saveAndFlush(any());
    }

    @Test
    void overnightClockInBelongsToPreviousWorkDate() {
        LocalDate workDate = LocalDate.of(2026, 10, 1);
        LocalDateTime punchAt = workDate.plusDays(1).atTime(2, 0);
        when(policyService.resolve(1L, workDate)).thenReturn(new ScheduleSnapshot(
                workDate, DayType.WORKDAY, WorkMode.OFFICE,
                workDate.atTime(22, 0), workDate.plusDays(1).atTime(6, 0), 60, true));
        org.junit.jupiter.api.Assertions.assertEquals(workDate,
                attendanceService.determineWorkDate(1L, punchAt));
    }
}
