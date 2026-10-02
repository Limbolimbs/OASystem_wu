package com.example.demo.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.attendance.AttendanceDayView;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.AttendancePolicy;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.AttendanceDayService;
import com.example.demo.service.AttendancePolicyService;
import com.example.demo.service.AttendanceRequestService;
import com.example.demo.service.AttendanceService;

@WebMvcTest(controllers = {AttendanceController.class, AttendanceManagementController.class})
class AttendancePageTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccessControlService access;
    @MockitoBean AttendanceService attendance;
    @MockitoBean AttendanceDayService days;
    @MockitoBean AttendanceRequestService requests;
    @MockitoBean AttendancePolicyService policies;
    @MockitoBean UserRepository users;

    @Test
    void employeeAttendancePageRendersJapaneseStatus() throws Exception {
        User user = user("EMPLOYEE");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        when(access.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(user));
        when(attendance.today()).thenReturn(today);
        when(days.evaluate(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    LocalDate date = call.getArgument(1);
                    ScheduleSnapshot schedule = new ScheduleSnapshot(date, DayType.WORKDAY,
                            WorkMode.OFFICE, date.atTime(9, 0), date.atTime(18, 0), 60, false);
                    return new AttendanceDayView(1L, user.getRealName(), date,
                            schedule, null, null, 0, 0, List.of("出勤未打刻"));
                });

        mvc.perform(get("/attendance"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("出勤未打刻")));
    }

    @Test
    void adminManagementPageRendersRuleAndScheduleForms() throws Exception {
        User user = user("ADMIN");
        when(access.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(user));
        when(access.isAdmin(user)).thenReturn(true);
        when(access.canApproveAttendance(user)).thenReturn(true);
        when(requests.ownRequests(1L, 0)).thenReturn(Page.empty());
        when(requests.reviewQueue(0)).thenReturn(Page.empty());
        when(requests.reviewedBy(1L, 0)).thenReturn(Page.empty());
        when(policies.currentPolicy()).thenReturn(new AttendancePolicy());
        when(policies.holidays()).thenReturn(List.of());
        when(users.findByStatusOrderByIdAsc(1)).thenReturn(List.of(user));
        when(policies.schedulesForDate(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        mvc.perform(get("/attendance/manage"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("会社共通の勤務ルール")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("個別勤務予定")));
    }

    @Test
    void employeeCannotChangePolicyOrSeeApprovalQueue() throws Exception {
        User user = user("EMPLOYEE");
        when(access.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(user));
        when(requests.ownRequests(1L, 0)).thenReturn(Page.empty());

        mvc.perform(get("/attendance/manage"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("会社共通の勤務ルール"))));

        mvc.perform(post("/attendance/manage/policy")
                        .param("standardStart", "09:00")
                        .param("standardEnd", "18:00")
                        .param("breakMinutes", "60")
                        .param("lateGraceMinutes", "0")
                        .param("earlyGraceMinutes", "0")
                        .param("overtimeThresholdMinutes", "0")
                        .param("workDay", "1", "2", "4", "8", "16"))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotReadAnotherUsersHistory() throws Exception {
        User user = user("EMPLOYEE");
        when(access.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(user));
        mvc.perform(get("/attendance/history")
                        .param("userId", "2")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-02"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewerCanReadDeactivatedUsersHistory() throws Exception {
        User reviewer = user("GENERAL_AFFAIRS");
        User formerEmployee = user("EMPLOYEE");
        formerEmployee.setId(2L);
        formerEmployee.setStatus(0);
        formerEmployee.setRealName("退職者 太郎");
        when(access.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(reviewer));
        when(access.canViewAllAttendance(reviewer)).thenReturn(true);
        when(users.findById(2L)).thenReturn(Optional.of(formerEmployee));
        when(users.findAllByOrderByStatusDescIdAsc()).thenReturn(List.of(reviewer, formerEmployee));
        when(days.evaluate(org.mockito.ArgumentMatchers.eq(formerEmployee), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    LocalDate date = call.getArgument(1);
                    ScheduleSnapshot schedule = new ScheduleSnapshot(date, DayType.REST_DAY,
                            WorkMode.OFFICE, null, null, 0, false);
                    return new AttendanceDayView(2L, formerEmployee.getRealName(), date,
                            schedule, null, null, 0, 0, List.of("所定休日"));
                });

        mvc.perform(get("/attendance/history")
                        .param("userId", "2")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-02"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("退職者 太郎")));
    }

    private User user(String role) {
        User user = new User();
        user.setId(1L);
        user.setStatus(1);
        user.setRole(role);
        user.setRealName("山田太郎");
        user.setUsername("yamada");
        return user;
    }
}
