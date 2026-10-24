package com.example.demo.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.attendance.AttendanceDayView;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.AttendancePolicy;
import com.example.demo.entity.User;
import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.AttendanceDayService;
import com.example.demo.service.AttendancePolicyService;
import com.example.demo.service.AttendanceRequestService;
import com.example.demo.service.AttendanceService;

@WebMvcTest(controllers = {AttendanceController.class, AttendanceManagementController.class},
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                classes = {com.example.demo.config.WebConfig.class, com.example.demo.config.LoginInterceptor.class}))
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
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

    @Test
    void requestSubmitReturnsJsonWithoutRedirect() throws Exception {
        User employee = user("EMPLOYEE");
        when(access.currentUser(any())).thenReturn(Optional.of(employee));
        mvc.perform(post("/attendance/manage/requests").header("X-Requested-With", "XMLHttpRequest")
                        .param("type", "LEAVE").param("startDate", "2026-10-08")
                        .param("endDate", "2026-10-08").param("reason", "私用のため"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(header().doesNotExist("Location"));
        verify(requests).submit(1L, AttendanceRequestType.LEAVE, LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 8), null, null, null, "私用のため");
    }

    @Test
    void invalidRequestPreservesBusinessErrorAsJson() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.of(user("EMPLOYEE")));
        doThrow(new IllegalArgumentException("対象期間を確認してください。"))
                .when(requests).submit(any(), any(), any(), any(), any(), any(), any(), any());
        mvc.perform(post("/attendance/manage/requests").header("X-Requested-With", "XMLHttpRequest")
                        .param("type", "LEAVE").param("startDate", "2026-10-08")
                        .param("endDate", "2026-10-08").param("reason", "私用のため"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("対象期間を確認してください。"));
    }

    @Test
    void expiredSessionAjaxReturns401NotLoginHtml() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.empty());
        mvc.perform(post("/attendance/manage/requests/2/cancel").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.redirectUrl").value("/"));
        mvc.perform(get("/attendance").param("fragment", "attendance-records")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCannotFetchPrivilegedFragmentsOrApproveByAjax() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.of(user("EMPLOYEE")));
        for (String fragment : List.of("review", "reviewed", "rules", "schedule-list", "holiday-list")) {
            mvc.perform(get("/attendance/manage").param("fragment", fragment)
                            .header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
        }
        mvc.perform(post("/attendance/manage/review/2/approve").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(requests, policies);
    }

    @Test
    void generalAffairsApprovalAndCancellationReturnJson() throws Exception {
        User reviewer = user("GENERAL_AFFAIRS");
        when(access.currentUser(any())).thenReturn(Optional.of(reviewer));
        when(access.canApproveAttendance(reviewer)).thenReturn(true);
        mvc.perform(post("/attendance/manage/review/2/approve").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/attendance/manage/requests/3/cancel").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(requests).review(1L, 2L, true, null);
        verify(requests).cancel(1L, 3L);
    }

    @Test
    void clockActionsReturnJson() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.of(user("EMPLOYEE")));
        for (String action : List.of("clock-in", "clock-out", "break-start", "break-end")) {
            mvc.perform(post("/attendance/" + action).header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(header().doesNotExist("Location"));
        }
        verify(attendance).clockIn(1L);
        verify(attendance).clockOut(1L);
        verify(attendance).startBreak(1L);
        verify(attendance).endBreak(1L);
    }

    @Test
    void managementFragmentDoesNotRenderWholePageOrQueryOtherPanels() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.of(user("EMPLOYEE")));
        when(requests.ownRequests(1L, 2)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 60));
        mvc.perform(get("/attendance/manage").param("ownPage", "2").param("fragment", "history"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"history\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ownPage=2")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<html"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("新規申請"))));
        verifyNoInteractions(policies);
    }

    @Test
    void emptyFinalQueuePageFallsBackToRemainingPage() throws Exception {
        User reviewer = user("GENERAL_AFFAIRS");
        when(access.currentUser(any())).thenReturn(Optional.of(reviewer));
        when(access.canApproveAttendance(reviewer)).thenReturn(true);
        when(requests.reviewQueue(1)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 0));
        when(requests.reviewQueue(0)).thenReturn(Page.empty());
        mvc.perform(get("/attendance/manage").param("reviewPage", "1").param("fragment", "review"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 / 1 ページ")));
        verify(requests).reviewQueue(0);
    }

    @Test
    void unknownFragmentIsRejectedWithoutQueries() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.of(user("ADMIN")));
        mvc.perform(get("/attendance/manage").param("fragment", "request")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(requests, policies);
    }

    @Test
    void scheduleAndHolidayFragmentsRenderForAdminWithoutLoadingPolicy() throws Exception {
        User admin = user("ADMIN");
        when(access.currentUser(any())).thenReturn(Optional.of(admin));
        when(access.isAdmin(admin)).thenReturn(true);
        when(policies.schedulesForDate(any())).thenReturn(List.of());
        when(policies.holidays()).thenReturn(List.of());
        for (String fragment : List.of("schedule-list", "holiday-list")) {
            mvc.perform(get("/attendance/manage").param("scheduleDate", "2026-10-08").param("fragment", fragment))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"" + fragment + "\"")));
        }
        org.mockito.Mockito.verify(policies, org.mockito.Mockito.never()).currentPolicy();
    }

    @Test
    void historyTitleFragmentUpdatesSelectedEmployee() throws Exception {
        User reviewer = user("GENERAL_AFFAIRS");
        User target = user("EMPLOYEE");
        target.setId(2L);
        target.setRealName("田中 太郎");
        when(access.currentUser(any())).thenReturn(Optional.of(reviewer));
        when(access.canViewAllAttendance(reviewer)).thenReturn(true);
        when(users.findById(2L)).thenReturn(Optional.of(target));
        mvc.perform(get("/attendance/history").param("userId", "2").param("from", "2026-10-08")
                        .param("to", "2026-10-08").param("fragment", "history-title"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("田中 太郎 の勤務予定と打刻履歴")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<html"))));
        verifyNoInteractions(days);
    }

    @Test
    void policyScheduleAndHolidayMutationsReturnJson() throws Exception {
        User admin = user("ADMIN");
        when(access.currentUser(any())).thenReturn(Optional.of(admin));
        when(access.canManageAttendancePolicy(admin)).thenReturn(true);
        mvc.perform(post("/attendance/manage/policy").header("X-Requested-With", "XMLHttpRequest")
                        .param("standardStart", "09:00").param("standardEnd", "18:00")
                        .param("breakMinutes", "60").param("lateGraceMinutes", "5")
                        .param("earlyGraceMinutes", "0").param("overtimeThresholdMinutes", "0")
                        .param("workDay", "1", "2", "4", "8", "16"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(policies).updatePolicy(1L, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0),
                60, 5, 0, 0, 31);
        mvc.perform(post("/attendance/manage/schedules").header("X-Requested-With", "XMLHttpRequest")
                        .param("userId", "2").param("workDate", "2026-10-08")
                        .param("dayType", "REST_DAY").param("workMode", "OFFICE").param("breakMinutes", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/attendance/manage/holidays").header("X-Requested-With", "XMLHttpRequest")
                        .param("date", "2026-10-08").param("name", "創立記念日"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/attendance/manage/holidays/2026-10-08/delete").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/attendance/manage/schedules/5/delete").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void attendanceResultFragmentRetainsSelectedDateWithoutRequeryingClockCards() throws Exception {
        User employee = user("EMPLOYEE");
        LocalDate date = LocalDate.of(2026, 10, 8);
        when(access.currentUser(any())).thenReturn(Optional.of(employee));
        ScheduleSnapshot schedule = new ScheduleSnapshot(date, DayType.WORKDAY,
                WorkMode.OFFICE, date.atTime(9, 0), date.atTime(18, 0), 60, false);
        when(days.evaluate(employee, date)).thenReturn(new AttendanceDayView(1L, employee.getRealName(),
                date, schedule, null, null, 0, 0, List.of("出勤未打刻")));
        mvc.perform(get("/attendance").param("date", "2026-10-08").param("fragment", "attendance-records"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("date=2026-10-08")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("出勤を打刻"))));
        org.mockito.Mockito.verify(attendance, org.mockito.Mockito.never()).findOpenRecord(any());
    }

    @Test
    void approvalFragmentRendersActionsForAnotherEmployeesPendingRequest() throws Exception {
        User reviewer = user("GENERAL_AFFAIRS");
        User employee = user("EMPLOYEE");
        employee.setId(2L);
        com.example.demo.entity.AttendanceRequest item = new com.example.demo.entity.AttendanceRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(item, "id", 5L);
        item.setUser(employee);
        item.setRequestType(AttendanceRequestType.LEAVE);
        item.setStartDate(LocalDate.of(2026, 10, 8));
        item.setEndDate(LocalDate.of(2026, 10, 8));
        item.setReason("私用のため");
        when(access.currentUser(any())).thenReturn(Optional.of(reviewer));
        when(access.canApproveAttendance(reviewer)).thenReturn(true);
        when(requests.reviewQueue(0)).thenReturn(new PageImpl<>(List.of(item)));
        mvc.perform(get("/attendance/manage").param("fragment", "review"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-ajax-lock=\"review:5\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/attendance/manage/review/5/approve")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/attendance/manage/review/5/reject")));
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
