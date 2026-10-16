package com.example.demo;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import com.example.demo.entity.User;
import com.example.demo.repository.DepartmentRepository;
import com.example.demo.repository.SalesDataRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 専用MySQLと試験社員のみを使用する。本番TLS・接続制限の検証とは区別する。 */
@EnabledIfEnvironmentVariable(named = "OA_ACCEPTANCE_TEST", matches = "true")
class IsolatedDatabaseIntegrationTest extends com.example.demo.acceptance.IsolatedIntegrationSupport {
    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired SalesDataRepository sales;
    @Autowired DashboardService dashboard;
    @Autowired com.example.demo.service.UserService accountService;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.example.demo.security.SessionLifecycle sessionLifecycle;

    private static final List<String> TABLES = List.of("sys_user", "department_master", "sales_data",
        "attendance_record", "attendance_policy", "attendance_policy_history", "company_holiday",
        "work_schedule", "attendance_request", "attendance_break", "attendance_event", "attendance_audit_log", "notice", "notice_read");

    @BeforeEach void requireIsolatedTarget() throws Exception {
        try (var connection = dataSource.getConnection()) {
            com.example.demo.acceptance.IsolatedDatabase.require(connection);
        }
    }

    private User administrator() {
        return users.findByStatusOrderByIdAsc(1).stream().filter(user -> "ADMIN".equals(user.getRole()))
            .findFirst().orElseThrow(() -> new IllegalStateException("有効な管理者が必要です。"));
    }

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", user.getId());
        session.setAttribute("loginUser", user.getUsername());
        session.setAttribute("loginRealName", user.getRealName());
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
            new com.example.demo.security.PortalPrincipal(user.getId(), user.getUsername()), null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + user.getRole())));
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new org.springframework.security.core.context.SecurityContextImpl(authentication));
        sessionLifecycle.start(session, user);
        return session;
    }

    @Test void embeddedServerServesLoginAndRejectsUnauthenticatedDashboard() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String origin = "http://127.0.0.1:" + port;
        var login = client.send(HttpRequest.newBuilder(URI.create(origin + "/"))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), "ログイン画面を開けません。");
        assertTrue(login.body().contains("https://code.jquery.com/jquery-3.7.1.min.js"));
        var response = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/dashboard"))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(401, response.statusCode());
    }

    @Test void allBusinessTablesHaveLogicalDeleteAndConsistentDateTime() {
        for (String table : TABLES) {
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema=DATABASE() AND table_name=? AND column_name IN ('delete_flag','deleted_at')",
                Integer.class, table), table);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
            + " WHERE table_schema=DATABASE() AND data_type='datetime' AND datetime_precision<>6", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE"
            + " (status=1 AND delete_flag<>0) OR (status=0 AND delete_flag<>1)", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM department_master WHERE"
            + " (status=1 AND delete_flag<>0) OR (status=0 AND delete_flag<>1)", Integer.class));
    }

    @Test
    void dashboardTotalsMatchActualDatabaseRows() {
        jdbc.update("INSERT INTO sales_data(sales_month,department,sales_amount,order_count,customer_count,notes,version,created_at,updated_at,delete_flag,deleted_at) VALUES('2026-08','QA営業一部',12345.67,12,4,'隔離集計',0,NOW(6),NOW(6),0,NULL),('2026-09','QA営業二部',22222.33,23,5,'隔離集計',0,NOW(6),NOW(6),0,NULL),('2026-10','QA営業一部',99999,99,99,'論理廃止の除外確認',0,NOW(6),NOW(6),1,NOW(6))");
        Map<?, ?> summary = (Map<?, ?>) dashboard.getDashboardData().get("summary");
        BigDecimal total = jdbc.queryForObject("SELECT COALESCE(SUM(sales_amount),0) FROM sales_data WHERE delete_flag=0", BigDecimal.class);
        assertEquals(0, total.compareTo((BigDecimal) summary.get("totalSales")));
        assertEquals(0,new BigDecimal("34568.00").compareTo(total),"非ゼロの複数部署実績を集計し、廃止行を除外します。");
        assertEquals(jdbc.queryForObject("SELECT COALESCE(SUM(order_count),0) FROM sales_data WHERE delete_flag=0", Long.class), summary.get("totalOrders"));
        assertEquals(jdbc.queryForObject("SELECT COALESCE(SUM(customer_count),0) FROM sales_data WHERE delete_flag=0", Long.class), summary.get("totalCustomers"));
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE status=1 AND delete_flag=0", Long.class), summary.get("employeeCount"));
    }

    @Test
    void pagesAndAccessChecksUseIsolatedData() throws Exception {
        User admin = administrator();
        for (String url : List.of("/home", "/users", "/users/new", "/departments", "/sales",
                "/attendance", "/attendance/history", "/attendance/manage")) {
            mvc.perform(get(url).session(session(admin))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/dashboard").session(session(admin)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.totalSales").isNumber());
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        for (User user : users.findByStatusOrderByIdAsc(1)) {
            if ("ADMIN".equals(user.getRole())) continue;
            mvc.perform(get("/api/dashboard").session(session(user))).andExpect(status().isForbidden());
            mvc.perform(get("/departments").session(session(user))).andExpect(status().isForbidden());
        }
    }

    @Test void employeeRegistrationStoresHashAndLeavesNoTestAccount() {
        User admin = administrator();
        String loginId = "EMP_VERIFY_" + java.util.UUID.randomUUID().toString().substring(0, 12);
        String password = "Abcdefgh123!";
        long originalCount = users.count();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            try {
                mvc.perform(post("/users/save").session(session(admin))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("username", loginId).param("password", password).param("passwordConfirmation", password)
                        .param("realName", "登録疎通確認").param("department", "").param("role", "EMPLOYEE"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
                User registered = users.findByUsername(loginId).orElseThrow();
                assertTrue(com.example.demo.security.PasswordHashes.isSupported(registered.getPassword()));
                assertTrue(com.example.demo.security.PasswordHashes.encoder().matches(password, registered.getPassword()));
                assertNotNull(accountService.findLoginUser(loginId, password));
            } catch (Exception exception) { throw new IllegalStateException("隔離社員登録の確認に失敗しました。", exception); }
        });
        assertFalse(users.existsByUsername(loginId), "テスト用社員が残っています。");
        assertEquals(originalCount, users.count());
    }

    @Test void salesAjaxSaveIsVisibleToDashboardAndLeavesNoTestRecord() {
        User admin = administrator();
        String department = departments.findByStatusAndSalesEnabledTrueOrderByIdAsc(1).stream()
            .findFirst().orElseThrow(() -> new IllegalStateException("有効な営業部署が必要です。")).getName();
        String selectedMonth = null;
        for (int year = 9999; year >= 9900; year--) {
            String candidate = year + "-12";
            if (!sales.existsBySalesMonthAndDepartment(candidate, department)) { selectedMonth = candidate; break; }
        }
        assertNotNull(selectedMonth, "テスト用の空き年月がありません。");
        final String month = selectedMonth;
        final BigDecimal amount = new BigDecimal("1234.56");
        long originalCount = sales.count();
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly(); // 成功・失敗のどちらでもコミットしない。
            Map<?, ?> before = (Map<?, ?>) dashboard.getDashboardData().get("summary");
            try {
                mvc.perform(post("/sales/save").session(session(admin)).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                    .header("X-Requested-With", "XMLHttpRequest")
                    .param("salesMonth", month).param("department", department)
                    .param("salesAmount", amount.toPlainString()).param("orderCount", "2")
                    .param("customerCount", "1").param("notes", "隔離疎通確認（自動ロールバック）")
                    .param("confirmed", "true"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
                assertTrue(sales.existsBySalesMonthAndDepartment(month, department));
                Map<?, ?> after = (Map<?, ?>) dashboard.getDashboardData().get("summary");
                assertEquals(0, ((BigDecimal) before.get("totalSales")).add(amount).compareTo((BigDecimal) after.get("totalSales")));
                assertEquals(((Number) before.get("totalOrders")).longValue() + 2, ((Number) after.get("totalOrders")).longValue());
                assertEquals(((Number) before.get("totalCustomers")).longValue() + 1, ((Number) after.get("totalCustomers")).longValue());
                mvc.perform(get("/api/dashboard").session(session(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.months", hasItem(month)));
            } catch (Exception error) {
                throw new IllegalStateException("営業実績の保存・集計確認に失敗しました。", error);
            }
        });
        assertFalse(sales.existsBySalesMonthAndDepartment(month, department), "テスト行が残っています。");
        assertEquals(originalCount, sales.count(), "営業実績の件数が変わっています。");
    }
}
