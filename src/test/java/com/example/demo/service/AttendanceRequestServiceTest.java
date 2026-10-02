package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.RequestStatus;
import com.example.demo.entity.AttendanceRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.AttendanceEventRepository;
import com.example.demo.repository.AttendanceAuditLogRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.AttendanceRequestRepository;
import com.example.demo.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AttendanceRequestServiceTest {
    @Mock AttendanceRequestRepository requests;
    @Mock AttendanceRecordRepository records;
    @Mock AttendanceEventRepository events;
    @Mock AttendanceAuditLogRepository audit;
    @Mock AttendancePolicyService policies;
    @Mock UserRepository users;
    @InjectMocks AttendanceRequestService service;
    private User employee;
    private AttendanceRequest request;

    @BeforeEach
    void setUp() {
        employee = new User();
        employee.setId(1L);
        employee.setRole("EMPLOYEE");
        employee.setStatus(1);
        request = new AttendanceRequest();
        request.setUser(employee);
        request.setRequestType(AttendanceRequestType.LEAVE);
        request.setStartDate(LocalDate.now());
        request.setEndDate(LocalDate.now());
    }

    @Test
    void employeeCannotReview() {
        when(requests.findById(12L)).thenReturn(Optional.of(request));
        User other = new User();
        other.setId(2L);
        other.setRole("EMPLOYEE");
        other.setStatus(1);
        when(users.findById(2L)).thenReturn(Optional.of(other));
        assertThrows(IllegalStateException.class, () -> service.review(2L, 12L, true, null));
        verify(policies, never()).assignSchedule(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void adminCannotApproveOwnRequest() {
        employee.setRole("ADMIN");
        when(requests.findById(12L)).thenReturn(Optional.of(request));
        when(users.findById(1L)).thenReturn(Optional.of(employee));
        assertThrows(IllegalStateException.class, () -> service.review(1L, 12L, true, null));
    }

    @Test
    void employeeCanCancelOnlyOwnPendingRequest() {
        when(requests.findById(12L)).thenReturn(Optional.of(request));
        service.cancel(1L, 12L);
        assertEquals(RequestStatus.CANCELLED, request.getStatus());
        assertThrows(IllegalStateException.class, () -> service.cancel(2L, 12L));
    }

    @Test
    void duplicatePendingRequestIsRejected() {
        LocalDate date = LocalDate.now();
        when(users.findById(1L)).thenReturn(Optional.of(employee));
        when(requests.findByUser_IdAndRequestTypeAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                1L, AttendanceRequestType.LEAVE, RequestStatus.PENDING, date, date))
                .thenReturn(List.of(request));
        assertThrows(IllegalStateException.class, () -> service.submit(1L,
                AttendanceRequestType.LEAVE, date, date, null, null, null, "私用"));
    }

    @Test
    void leaveApprovalSkipsRestDays() {
        LocalDate saturday = LocalDate.of(2026, 10, 3);
        LocalDate sunday = saturday.plusDays(1);
        LocalDate monday = sunday.plusDays(1);
        request.setStartDate(saturday);
        request.setEndDate(monday);
        User reviewer = new User();
        reviewer.setId(2L);
        reviewer.setStatus(1);
        reviewer.setRole("GENERAL_AFFAIRS");
        when(requests.findById(12L)).thenReturn(Optional.of(request));
        when(users.findById(2L)).thenReturn(Optional.of(reviewer));
        when(policies.resolve(1L, saturday)).thenReturn(new ScheduleSnapshot(
                saturday, DayType.REST_DAY, WorkMode.OFFICE, null, null, 0, false));
        when(policies.resolve(1L, sunday)).thenReturn(new ScheduleSnapshot(
                sunday, DayType.REST_DAY, WorkMode.OFFICE, null, null, 0, false));
        when(policies.resolve(1L, monday)).thenReturn(new ScheduleSnapshot(
                monday, DayType.WORKDAY, WorkMode.OFFICE,
                monday.atTime(9, 0), monday.atTime(18, 0), 60, false));

        service.review(2L, 12L, true, "承認");

        assertEquals(RequestStatus.APPROVED, request.getStatus());
        verify(policies).assignSchedule(2L, 1L, monday, DayType.LEAVE,
                WorkMode.OFFICE, null, null, 0);
        verify(policies, never()).assignSchedule(2L, 1L, saturday, DayType.LEAVE,
                WorkMode.OFFICE, null, null, 0);
    }
}
