package com.example.demo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import com.example.demo.entity.Department;
import com.example.demo.entity.SalesData;
import com.example.demo.entity.User;
import com.example.demo.sales.SalesForm;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({SalesController.class, DepartmentController.class, HomeController.class, DashboardApiController.class})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
class BusinessPageTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccessControlService access;
    @MockitoBean DepartmentService departments;
    @MockitoBean SalesService sales;
    @MockitoBean UserService users;
    @MockitoBean DashboardService dashboard;
    User actor;
    MockHttpSession session;

    @BeforeEach void setUp() {
        actor = new User(); actor.setId(2L); actor.setRole("EMPLOYEE"); actor.setDepartment("営業部"); actor.setRealName("田中 太郎");
        session = new MockHttpSession(); session.setAttribute("loginUserId", 2L); session.setAttribute("loginRealName", actor.getRealName());
        when(access.currentUser(any())).thenReturn(Optional.of(actor));
        when(access.canManageSales(actor)).thenReturn(true);
        when(users.findById(2L)).thenReturn(Optional.of(actor));
        when(departments.isSalesDepartment("営業部")).thenReturn(true);
        Department department = new Department(); department.setId(1L); department.setCode("SALES"); department.setName("営業部"); department.setSalesEnabled(true);
        department.setVersion(0L);
        when(departments.activeDepartments()).thenReturn(List.of(department));
        when(departments.findAll()).thenReturn(List.of(department));
        SalesData row = new SalesData(); row.setId(1L); row.setSalesMonth("2026-09"); row.setDepartment("営業部");
        row.setVersion(0L);
        row.setSalesAmount(new BigDecimal("18500000")); row.setOrderCount(920); row.setCustomerCount(95);
        when(sales.findVisible(actor)).thenReturn(List.of(row));
        when(sales.findEditable(actor, 1L)).thenReturn(row);
    }

    @Test void salesLoginDestinationAndAdminPriority() throws Exception {
        mvc.perform(get("/home").session(session)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/sales"));
        actor.setRole("ADMIN");
        mvc.perform(get("/home").session(session)).andExpect(status().isOk()).andExpect(view().name("home"));
        actor.setRole("EMPLOYEE"); actor.setDepartment("総務部");
        mvc.perform(get("/home").session(session)).andExpect(redirectedUrl("/attendance"));
    }

    @Test void invalidLoginStateDoesNotRouteToSales() throws Exception {
        mvc.perform(get("/home")).andExpect(redirectedUrl("/"));
        actor.setStatus(0);
        mvc.perform(get("/home").session(session)).andExpect(redirectedUrl("/"));
    }

    @Test void salesPageUsesOwnDepartmentLocalScriptsAndSharedMenu() throws Exception {
        String html = mvc.perform(get("/sales").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("営業実績管理")))
                .andExpect(content().string(containsString("/js/sales.js")))
                .andExpect(content().string(containsString("https://code.jquery.com/jquery-3.7.1.min.js")))
                .andExpect(content().string(containsString("18,500,000.00")))
                .andExpect(content().string(not(containsString("href=\"/departments\""))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // オフライン画面確認用の生成物。実DB・Webサーバーには接続しない。
        Files.createDirectories(Path.of("target/ui-preview"));
        Files.writeString(Path.of("target/ui-preview/sales.html"), html, StandardCharsets.UTF_8);
    }

    @Test void adminPagesRenderDepartmentControls() throws Exception {
        actor.setRole("ADMIN"); when(access.isAdmin(actor)).thenReturn(true);
        String html = mvc.perform(get("/departments").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("営業実績登録")))
                .andExpect(content().string(containsString("data-ajax-targets=\"department-data\"")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Files.createDirectories(Path.of("target/ui-preview"));
        Files.writeString(Path.of("target/ui-preview/departments.html"), html, StandardCharsets.UTF_8);
        mvc.perform(get("/sales").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"salesDepartment\" name=\"department\" required")));
    }

    @Test void salesSaveBindsTeacherStyleFormDataAndReturnsJsonWithoutRedirect() throws Exception {
        mvc.perform(post("/sales/save").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("salesMonth", "2026-09").param("department", "営業部")
                        .param("salesAmount", "1200000.50").param("orderCount", "20").param("customerCount", "10")
                        .param("notes", "照合済み").param("confirmed", "true").param("updatedBy", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(header().doesNotExist("Location"));
        ArgumentCaptor<SalesForm> form = ArgumentCaptor.forClass(SalesForm.class);
        verify(sales).save(eq(actor), form.capture());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("1200000.50"), form.getValue().salesAmount());
        org.junit.jupiter.api.Assertions.assertTrue(form.getValue().confirmed());
        org.junit.jupiter.api.Assertions.assertNull(form.getValue().id());
    }

    @Test void recordBindingAcceptsEditVersionAndRejectsInvalidNumbers() throws Exception {
        mvc.perform(post("/sales/save").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("id", "1").param("version", "3").param("salesMonth", "2026-09")
                        .param("department", "営業部").param("salesAmount", "1").param("orderCount", "2")
                        .param("customerCount", "3").param("confirmed", "true"))
                .andExpect(status().isOk());
        mvc.perform(post("/sales/save").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("salesAmount", "not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    @Test void salesFragmentsAndDetailsOnlyReturnRequestedData() throws Exception {
        mvc.perform(get("/sales").session(session).param("fragment", "sales-data"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"sales-data\"")))
                .andExpect(content().string(not(containsString("<html"))))
                .andExpect(content().string(not(containsString("<script"))));
        mvc.perform(get("/sales/1").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("営業部")).andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/sales").session(session).param("fragment", "sales-entry").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isBadRequest());
    }

    @Test void normalEmployeeCannotAccessSalesOrDepartmentWrite() throws Exception {
        when(access.canManageSales(actor)).thenReturn(false);
        mvc.perform(post("/sales/save").session(session).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/sales/1").session(session).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/departments").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("code", "SALES_2").param("name", "営業第二部"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/departments/1").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("version", "0").param("status", "1"))
                .andExpect(status().isForbidden());
        verify(sales, never()).save(any(), any()); verify(departments, never()).create(any(), any(), anyBoolean());
    }

    @Test void expiredSessionReturns401ForAllBusinessAjax() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.empty());
        mvc.perform(post("/sales/save").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.redirectUrl").value("/"));
        mvc.perform(get("/departments").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test void adminDepartmentChangesUseJsonAndVersion() throws Exception {
        when(access.isAdmin(actor)).thenReturn(true);
        mvc.perform(post("/departments").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("code", "SALES_2").param("name", "営業第二部").param("salesEnabled", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(departments).create("SALES_2", "営業第二部", true);
        mvc.perform(post("/departments/1").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("version", "2").param("status", "1"))
                .andExpect(status().isOk());
        verify(departments).update(1L, 2L, false, 1);
    }

    @Test void salesStaffDoNotReceiveCompanyDashboardApi() throws Exception {
        mvc.perform(get("/api/dashboard").session(session)).andExpect(status().isForbidden());
        verify(dashboard, never()).getDashboardData();
    }
}
