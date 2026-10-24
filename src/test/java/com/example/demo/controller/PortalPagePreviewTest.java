package com.example.demo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.example.demo.attendance.*;
import com.example.demo.entity.*;
import com.example.demo.notice.*;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 実DB・資格情報を使用せず、実テンプレートの各権限表示を検証する。 */
@WebMvcTest(controllers = {HomeController.class, UserController.class, SalesController.class, DepartmentController.class,
        AttendanceController.class, AttendanceManagementController.class, NoticeController.class, ProfileController.class},
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                classes = {com.example.demo.config.WebConfig.class, com.example.demo.config.LoginInterceptor.class}))
@AutoConfigureMockMvc(addFilters = false)
class PortalPagePreviewTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccessControlService access;
    @MockitoBean UserService users;
    @MockitoBean DepartmentService departments;
    @MockitoBean SalesService sales;
    @MockitoBean AttendanceService attendance;
    @MockitoBean AttendanceDayService days;
    @MockitoBean AttendanceRequestService requests;
    @MockitoBean AttendancePolicyService policies;
    @MockitoBean NoticeService notices;
    @MockitoBean ProfileService profiles;
    @MockitoBean UserRepository userRepository;
    private User actor;
    private MockHttpSession session;
    private final LocalDate date = LocalDate.of(2026, 10, 15);

    @BeforeEach void setup() {
        actor = new User(); actor.setId(1L); actor.setUsername("EMP000101");
        actor.setRealName("確認用 管理者"); actor.setRole("ADMIN"); actor.setDepartment("総務部");
        session = new MockHttpSession(); session.setAttribute("loginUserId", 1L);
        session.setAttribute("loginRealName", actor.getRealName());
        when(users.findById(1L)).thenReturn(Optional.of(actor));
        when(users.findAll()).thenReturn(List.of(actor));
        when(access.currentUser(any())).thenReturn(Optional.of(actor));
        when(access.isAdmin(any())).thenAnswer(c -> "ADMIN".equals(actor.getRole()));
        when(access.canApproveAttendance(any())).thenAnswer(c -> !"EMPLOYEE".equals(actor.getRole()));
        when(access.canViewAllAttendance(any())).thenAnswer(c -> !"EMPLOYEE".equals(actor.getRole()));
        when(access.canManageSales(any())).thenAnswer(c -> "ADMIN".equals(actor.getRole()) || "営業部".equals(actor.getDepartment()));
        Department department = new Department(); department.setId(1L); department.setCode("SALES");
        department.setName("営業部"); department.setSalesEnabled(true); department.setVersion(0L);
        when(departments.findAll()).thenReturn(List.of(department));
        when(departments.activeDepartments()).thenReturn(List.of(department));
        when(departments.isSalesDepartment("営業部")).thenReturn(true);
        when(profiles.profile(any())).thenAnswer(call -> new ProfileService.Profile(actor.getUsername(), actor.getRealName(),
                actor.getDepartment(), "ADMIN".equals(actor.getRole()) ? "管理者" : "GENERAL_AFFAIRS".equals(actor.getRole()) ? "総務担当" : "一般社員",
                LocalDateTime.of(2026, 9, 28, 9, 0), "ADMIN".equals(actor.getRole()),
                "ADMIN".equals(actor.getRole()) || "営業部".equals(actor.getDepartment()), !"EMPLOYEE".equals(actor.getRole())));
        SalesData sale = new SalesData(); sale.setId(101L); sale.setVersion(0L); sale.setSalesMonth("2026-09");
        sale.setDepartment("営業部"); sale.setSalesAmount(new BigDecimal("18500000")); sale.setOrderCount(920); sale.setCustomerCount(95);
        when(sales.findVisible(any())).thenReturn(List.of(sale));
        when(attendance.today()).thenReturn(date);
        when(days.evaluate(any(), any())).thenAnswer(call -> day(call.getArgument(1)));
        when(days.allActiveForDate(any(), anyInt())).thenReturn(new PageImpl<>(List.of(day(date)), PageRequest.of(0, 20), 1));
        when(requests.ownRequests(anyLong(), anyInt())).thenReturn(Page.empty());
        when(requests.reviewQueue(anyInt())).thenReturn(Page.empty());
        when(requests.reviewedBy(anyLong(), anyInt())).thenReturn(Page.empty());
        when(policies.currentPolicy()).thenReturn(new AttendancePolicy());
        when(policies.holidays()).thenReturn(List.of());
        when(policies.schedulesForDate(any())).thenReturn(List.of());
        when(userRepository.findByStatusOrderByIdAsc(1)).thenReturn(List.of(actor));
        when(userRepository.findAllByOrderByStatusDescIdAsc()).thenReturn(List.of(actor));
        when(notices.canEdit(any())).thenAnswer(c -> !"EMPLOYEE".equals(actor.getRole()));
        var item = new NoticeService.Summary(101L, 1L, "業務連絡の確認", "一般", NoticePriority.NORMAL,
                NoticeStatus.PUBLISHED, "総務部", LocalDateTime.of(2026, 10, 15, 9, 0), LocalDateTime.of(2026, 10, 15, 9, 0), false, false);
        when(notices.list(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));
    }

    private AttendanceDayView day(LocalDate selected) {
        return new AttendanceDayView(1L, actor.getRealName(), selected,
                new ScheduleSnapshot(selected, DayType.WORKDAY, WorkMode.OFFICE,
                        selected.atTime(9, 0), selected.atTime(18, 0), 60, false),
                null, null, 0, 0, List.of("出勤未打刻"));
    }

    private String preview(String name, String url) throws Exception {
        String html = mvc.perform(get(url).session(session)
                        .principal(new com.example.demo.security.PortalPrincipal(1L, actor.getUsername()))
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "preview-only")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"mainContent\"")))
                .andExpect(content().string(containsString("id=\"portalNavigation\"")))
                .andExpect(content().string(containsString("data-logout")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Path output = Path.of(System.getProperty("oa.ui-preview", "target/ui-preview"));
        Files.createDirectories(output); Files.writeString(output.resolve(name + ".html"), html, StandardCharsets.UTF_8);
        return html;
    }

    @Test void allAdminScreensShareTheSameShell() throws Exception {
        for (var pair : List.of(new String[]{"home", "/home"}, new String[]{"users", "/users"},
                new String[]{"user-form", "/users/new"}, new String[]{"sales", "/sales"},
                new String[]{"departments", "/departments"}, new String[]{"attendance", "/attendance"},
                new String[]{"attendance-history", "/attendance/history"},
                new String[]{"attendance-manage", "/attendance/manage"}, new String[]{"notices", "/notices"}, new String[]{"my-page", "/my-page"})) {
            preview(pair[0], pair[1]);
        }
    }

    @Test void loginUsesSharedFeedbackWithoutAuthenticatedNavigation() throws Exception {
        String html = mvc.perform(get("/").requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "preview-only")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/js/interaction-feedback.js")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("id=\"portalNavigation\""));
        Path output = Path.of(System.getProperty("oa.ui-preview", "target/ui-preview"));
        Files.createDirectories(output); Files.writeString(output.resolve("login.html"), html, StandardCharsets.UTF_8);
    }

    @Test void employeeScreensDoNotGainAdminNavigationOrApprovalControls() throws Exception {
        actor.setRole("EMPLOYEE"); actor.setRealName("確認用 従業員");
        for (var pair : List.of(new String[]{"employee-attendance", "/attendance"},
                new String[]{"employee-requests", "/attendance/manage"}, new String[]{"employee-notices", "/notices"})) {
            String html = preview(pair[0], pair[1]);
            for (String restricted : List.of("href=\"/users\"", "href=\"/departments\"", "href=\"/sales\"", "id=\"review\"", "id=\"noticeForm\""))
                org.junit.jupiter.api.Assertions.assertFalse(html.contains(restricted), restricted);
        }
    }

    @Test void salesEmployeeKeepsSalesButNotCompanyManagementNavigation() throws Exception {
        actor.setRole("EMPLOYEE"); actor.setDepartment("営業部");
        String html = preview("sales-employee", "/sales");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("href=\"/sales\""));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("href=\"/departments\""));
    }

    @Test void myPageShowsReadOnlyCompanyIdentityAndRoleSpecificShortcuts() throws Exception {
        for (String role : List.of("EMPLOYEE", "GENERAL_AFFAIRS", "ADMIN")) {
            actor.setRole(role);
            actor.setRealName("確認用 " + ("ADMIN".equals(role) ? "管理者" : "GENERAL_AFFAIRS".equals(role) ? "総務担当" : "従業員"));
            String html = preview("profile-" + role.toLowerCase(java.util.Locale.ROOT), "/my-page");
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("action=\"/my-page/password\""));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("EMP000101"));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("name=\"role\""));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("name=\"userId\""));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("name=\"realName\""));
            org.junit.jupiter.api.Assertions.assertEquals("ADMIN".equals(role), html.contains("href=\"/users\""));
            org.junit.jupiter.api.Assertions.assertEquals("ADMIN".equals(role), html.contains("href=\"/sales\""));
        }
        actor.setRole("EMPLOYEE"); actor.setDepartment("営業部");
        String html = preview("profile-sales", "/my-page");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("href=\"/sales\""));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("href=\"/users\""));
    }

    @Test void myPageEscapesCompanyDisplayValues() throws Exception {
        actor.setRealName("<script>unexpected()</script>");
        String html = preview("profile-escaped", "/my-page");
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("<script>unexpected()</script>"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("&lt;script&gt;unexpected()&lt;/script&gt;"));
    }

    @Test void partialUpdatesStillReturnOnlyTheRequestedRegion() throws Exception {
        for (var pair : List.of(new String[]{"employee-data", "/users"},
                new String[]{"employee-count", "/users"}, new String[]{"department-data", "/departments"},
                new String[]{"sales-data", "/sales"}, new String[]{"attendance-overview", "/attendance"},
                new String[]{"attendance-records", "/attendance"}, new String[]{"notice-data", "/notices"},
                new String[]{"notice-count", "/notices"}, new String[]{"history", "/attendance/manage"})) {
            String html = mvc.perform(get(pair[1]).session(session).param("fragment", pair[0]))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"" + pair[0] + "\""));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("portalNavigation"));
            Path output = Path.of(System.getProperty("oa.ui-preview", "target/ui-preview"));
            Files.createDirectories(output);
            Files.writeString(output.resolve("fragment-" + pair[0] + ".html"), html, StandardCharsets.UTF_8);
        }
    }
}
