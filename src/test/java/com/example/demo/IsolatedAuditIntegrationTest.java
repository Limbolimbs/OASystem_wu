package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.example.demo.security.*;
import com.example.demo.service.UserService;
import com.example.demo.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/** OAの認証・監査トランザクション。独立管理コンソールには接続しない。 */
@EnabledIfEnvironmentVariable(named = "OA_ACCEPTANCE_TEST", matches = "true")
class IsolatedAuditIntegrationTest extends com.example.demo.acceptance.IsolatedIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AuditService audit;
    @Autowired AccountSecurityState state;
    @Autowired UserRepository users;
    @Autowired DepartmentRepository departments;

    static class TestClock extends Clock {
        Instant instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
    @BeforeEach void checkTarget() throws Exception {
        var source = jdbc.getDataSource(); assertNotNull(source);
        try (var connection = source.getConnection()) {
            com.example.demo.acceptance.IsolatedDatabase.require(connection);
        }
    }
    LoginProtectionService service(TestClock clock, SecuritySettings settings, UserService fake) {
        return new LoginProtectionService(jdbc, fake, audit, clock, settings, manager);
    }
    String unique() { return "security-test-" + UUID.randomUUID(); }
    int count(String scope, String value) {
        return jdbc.queryForObject("SELECT attempts FROM security_login_attempt WHERE scope=? AND key_hash=?", Integer.class, scope, SecurityRequestContext.hash(value));
    }
    @Test void simultaneousFailuresPersistAcrossServiceRecreationAndBlockedAttemptsDoNotExtendTime() throws Exception {
        var clock = new TestClock(); var settings = new SecuritySettings(); var fake = mock(UserService.class);
        String id = unique(); String source = unique();
        var service = service(clock, settings, fake);
        try (var executor = Executors.newFixedThreadPool(5)) {
            List<Callable<LoginProtectionService.Outcome>> jobs = new ArrayList<>();
            for (int i = 0; i < 5; i++) jobs.add(() -> {
                SecurityRequestContext.begin(source);
                try { return service.login(id, "wrong", source); } finally { SecurityRequestContext.clear(); }
            });
            var futures = executor.invokeAll(jobs);
            int limited = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS).retryAfter() > 0) limited++;
            assertEquals(1, limited); assertEquals(5, count("ACCOUNT", "unknown:" + id));
        }
        clock.instant = clock.instant.plusSeconds(30);
        var recreated = service(clock, settings, fake);
        assertEquals(870, recreated.login(id.toUpperCase(Locale.ROOT), "wrong", source).retryAfter());
        assertEquals(5, count("ACCOUNT", "unknown:" + id));
        clock.instant = clock.instant.plusSeconds(870);
        assertEquals(0, recreated.login(id, "wrong", source).retryAfter());
        assertEquals(6, count("ACCOUNT", "unknown:" + id));
    }
    @Test void sourceLimitUsesAtomicFixedWindowAndServiceOutageRollsBackCounters() {
        var clock = new TestClock(); var settings = new SecuritySettings(); settings.setSourceLimit(2);
        var fake = mock(UserService.class); String source = unique(); var service = service(clock, settings, fake);
        assertEquals(0, service.login(unique(), "wrong", source).retryAfter());
        assertEquals(0, service.login(unique(), "wrong", source).retryAfter());
        assertEquals(60, service.login(unique(), "wrong", source).retryAfter());
        clock.instant = clock.instant.plusSeconds(10);
        assertEquals(50, service.login(unique(), "wrong", source).retryAfter());
        clock.instant = clock.instant.plusSeconds(50);
        assertEquals(0, service.login(unique(), "wrong", source).retryAfter());
        String failing = unique(); when(fake.findLoginUser(failing, "wrong")).thenThrow(new IllegalStateException("test outage"));
        assertThrows(IllegalStateException.class, () -> service.login(failing, "wrong", source));
        assertEquals(1, count("SOURCE", source));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?", Integer.class, SecurityRequestContext.hash("unknown:" + failing)));
    }
    @Test void manualProtectionStopsPasswordChecksAndKeepsCountersAndEmployeeRowsUnchanged() {
        var clock=new TestClock();var settings=new SecuritySettings();var fake=mock(UserService.class);
        String id=unique(),source=unique(),key=SecurityRequestContext.hash("unknown:"+id);
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,window_start,frozen) VALUES('ACCOUNT',?,CURRENT_TIMESTAMP(6),1)",key);
        long before=jdbc.queryForObject("SELECT SUM(authentication_version) FROM sys_user",Long.class);
        try{
            var result=service(clock,settings,fake).login(id,"wrong",source);
            assertTrue(result.protectedLogin());assertEquals(0,result.retryAfter());assertNull(result.user());
            verify(fake,never()).findLoginUser(any(),any());assertEquals(0,count("ACCOUNT","unknown:"+id));
            assertEquals(before,jdbc.queryForObject("SELECT SUM(authentication_version) FROM sys_user",Long.class));
        }finally{jdbc.update("UPDATE security_login_attempt SET frozen=0 WHERE scope='ACCOUNT' AND key_hash=?",key);}
    }

    public static class UpdateFixture {
        private final JdbcTemplate jdbc;
        UpdateFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @BusinessAudit(operation = "SECURITY_TEST_UPDATE", target = "TEST")
        public void update(String key, boolean reject) {
            jdbc.update("UPDATE security_login_attempt SET attempts=attempts+1 WHERE scope='ACCOUNT' AND key_hash=?", key);
            if (reject) throw new IllegalArgumentException("テスト拒否");
        }
    }
    UpdateFixture fixture(AuditService storage) {
        var factory = new AspectJProxyFactory(new UpdateFixture(jdbc));
        factory.setProxyTargetClass(true);
        factory.addAspect(new BusinessAuditAspect(manager, storage, state, users, departments));
        return factory.getProxy();
    }
    @Test void auditFailureRollsBackBusinessAndRejectedOperationRetainsItsAuditOnly() {
        String key = SecurityRequestContext.hash(unique());
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,window_start) VALUES('ACCOUNT',?,CURRENT_TIMESTAMP(6))", key);
        SecurityRequestContext.begin(unique()); String correlation = SecurityRequestContext.current().correlationId();
        try {
            var failing = mock(AuditService.class);
            doThrow(new IllegalStateException("test outage")).when(failing).record(any(), any(), any(), any(), any(), any(), any());
            assertThrows(AuditUnavailableException.class, () -> fixture(failing).update(key, false));
            assertEquals(0, jdbc.queryForObject("SELECT attempts FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?", Integer.class, key));
            assertThrows(IllegalArgumentException.class, () -> fixture(audit).update(key, true));
            assertEquals(0, jdbc.queryForObject("SELECT attempts FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?", Integer.class, key));
            fixture(audit).update(key, false);
            assertEquals(1, jdbc.queryForObject("SELECT attempts FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?", Integer.class, key));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_log WHERE correlation_id=?", Integer.class, correlation));
            var fields = jdbc.queryForList("SELECT operation_code,result,changes_json FROM security_audit_log WHERE correlation_id=?", correlation);
            assertTrue(fields.stream().allMatch(row -> row.get("changes_json") == null));
            assertTrue(fields.stream().anyMatch(row -> row.get("result").equals("REJECTED")));
        } finally { SecurityRequestContext.clear(); }
    }
    @Test void authenticationGenerationAndLastAdministratorGuardUseTransactionLocks() {
        var transaction = new TransactionTemplate(manager);
        long before = jdbc.queryForObject("SELECT SUM(authentication_version) FROM sys_user", Long.class);
        transaction.executeWithoutResult(status -> {
            state.lockManagement();
            assertTrue(state.activeAdministratorCount() >= 1);
            var admin = users.findByStatusOrderByIdAsc(1).stream().filter(u -> "ADMIN".equals(u.getRole())).findFirst().orElseThrow();
            state.revoke(admin); users.flush();
            assertEquals(before + 1, jdbc.queryForObject("SELECT SUM(authentication_version) FROM sys_user", Long.class));
            status.setRollbackOnly();
        });
        assertEquals(before, jdbc.queryForObject("SELECT SUM(authentication_version) FROM sys_user", Long.class));
    }
    @Test void supplementaryEvidenceFailureRollsBackOriginalAuditAndBusinessUpdate(){
        String key=SecurityRequestContext.hash(unique());
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,window_start) VALUES('ACCOUNT',?,CURRENT_TIMESTAMP(6))",key);
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/security-test");request.setRemoteAddr("203.0.113.14");
        SecurityRequestContext.begin(request);String id=SecurityRequestContext.current().correlationId();
        var failing=mock(AccessEvidenceWriter.class);doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic evidence outage")).when(failing).record(any(),any(),any());
        var storage=new AuditService(jdbc,Clock.systemUTC(),failing);
        try{
            assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.update("UPDATE security_login_attempt SET attempts=attempts+1 WHERE scope='ACCOUNT' AND key_hash=?",key);
                storage.record("SECURITY_TEST_UPDATE",null,"TEST",null,"SUCCESS",null,key);
            }));
            assertEquals(0,jdbc.queryForObject("SELECT attempts FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",Integer.class,key));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_log WHERE correlation_id=?",Integer.class,id));
        }finally{SecurityRequestContext.clear();}
    }
}
