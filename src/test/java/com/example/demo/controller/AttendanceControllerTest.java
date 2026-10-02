package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;

import com.example.demo.attendance.AttendanceDayView;
import com.example.demo.entity.User;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.AttendanceDayService;
import com.example.demo.service.AttendanceService;

@ExtendWith(MockitoExtension.class)
class AttendanceControllerTest {
    @Mock AttendanceService attendanceService;
    @Mock AttendanceDayService dayService;
    @Mock AccessControlService accessControl;
    @InjectMocks AttendanceController attendanceController;
    private MockHttpSession session;
    private User user;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        session.setAttribute("loginUserId", 1L);
        user = new User();
        user.setId(1L);
        user.setStatus(1);
    }

    @Test
    void employeeSeesOnlyOwnDay() {
        LocalDate date = LocalDate.of(2026, 9, 28);
        when(accessControl.currentUser(session)).thenReturn(Optional.of(user));
        when(attendanceService.today()).thenReturn(date);
        when(dayService.evaluate(user, date)).thenReturn(org.mockito.Mockito.mock(AttendanceDayView.class));
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("attendance", attendanceController.index(date, 0, session, model));
        assertFalse((Boolean) model.get("reviewer"));
        verify(dayService).evaluate(user, date);
        verify(dayService, never()).allActiveForDate(date, 0);
    }

    @Test
    void reviewerCanSeeAllEmployeesForSelectedDate() {
        LocalDate date = LocalDate.of(2026, 9, 28);
        when(accessControl.currentUser(session)).thenReturn(Optional.of(user));
        when(accessControl.canApproveAttendance(user)).thenReturn(true);
        when(attendanceService.today()).thenReturn(date);
        when(dayService.allActiveForDate(date, 0)).thenReturn(new PageImpl<>(List.of()));
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("attendance", attendanceController.index(date, 0, session, model));
        assertTrue((Boolean) model.get("reviewer"));
        verify(dayService).allActiveForDate(date, 0);
        verify(dayService, never()).evaluate(user, date);
    }

    @Test
    void missingSessionCannotSeeAttendance() {
        when(accessControl.currentUser(session)).thenReturn(Optional.empty());
        assertEquals("redirect:/", attendanceController.index(null, 0, session, new ExtendedModelMap()));
    }
}
