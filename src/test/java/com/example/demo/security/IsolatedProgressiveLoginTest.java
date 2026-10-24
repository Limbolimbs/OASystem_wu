package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpSession;
import com.zaxxer.hikari.*;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;

/** OAの段階的ログイン保護。専用DBと試験社員を使い、時計だけを進める。 */
@EnabledIfEnvironmentVariable(named="OA_ACCEPTANCE_TEST",matches="true")
class IsolatedProgressiveLoginTest {
    static class TestClock extends Clock {
        volatile Instant instant=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return instant;}
        void advance(Duration elapsed){instant=instant.plus(elapsed);}
    }
    HikariDataSource source;JdbcTemplate jdbc;JdbcTransactionManager manager;TestClock clock;User user;String login,remote,key;UserService users;AuditService audit;LoginProtectionService service;AccountProtection protection;
    @BeforeEach void setup()throws Exception{
        try(var connection=com.example.demo.acceptance.IsolatedDatabase.connection()){com.example.demo.acceptance.IsolatedDatabase.require(connection);}
        var config=new HikariConfig();config.setJdbcUrl(com.example.demo.acceptance.IsolatedDatabase.URL);config.setUsername("root");config.setPassword(com.example.demo.acceptance.IsolatedDatabase.PASSWORD);config.setMaximumPoolSize(5);config.setConnectionInitSql("SET time_zone='+09:00'");
        source=new HikariDataSource(config);jdbc=new JdbcTemplate(source);manager=new JdbcTransactionManager(source);clock=new TestClock();
        com.example.demo.acceptance.IsolatedFixtures.reset(source);
        user=new User();user.setId(91003L);user.setUsername("QA_EMP_3");user.setRole("EMPLOYEE");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=? AND username=? AND status=1",Integer.class,user.getId(),user.getUsername()));
        login=user.getUsername();remote="synthetic-source-"+UUID.randomUUID();key=SecurityRequestContext.hash("user:"+user.getId());
        users=mock(UserService.class);audit=mock(AuditService.class);when(users.findByUsername(login)).thenReturn(Optional.of(user));when(users.findLoginUser(login,"correct")).thenReturn(user);
        service=new LoginProtectionService(jdbc,users,audit,clock,new SecuritySettings(),manager);protection=new AccountProtection(jdbc);
        SecurityRequestContext.begin(remote);
    }
    @AfterEach void retire(){try{if(jdbc!=null&&key!=null)jdbc.update("UPDATE security_login_attempt SET attempts=0,frozen=0,blocked_until=NULL,failure_stage=0,protected_at=NULL,protection_reason=NULL,delete_flag=1,deleted_at=CURRENT_TIMESTAMP(6) WHERE (scope='ACCOUNT' AND key_hash=?) OR (scope='SOURCE' AND key_hash=?)",key,SecurityRequestContext.hash(remote));}finally{SecurityRequestContext.clear();if(source!=null)source.close();}}
    int attempts(){return jdbc.queryForObject("SELECT attempts FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",Integer.class,key);}
    void fail(int number){for(int i=0;i<number;i++)service.login(login,"wrong",remote);}
    @Test void allThreeTiersPersistAndProtectionSurvivesExpiryAndInvalidatesOldSession(){
        var first=service.login(login,"correct",remote);assertNotNull(first.user());
        var life=new SessionLifecycle(clock,new SecuritySettings());life.setProtection(protection);var old=new MockHttpSession();life.start(old,user,first.protectionVersion());
        fail(5);assertEquals(5,attempts());assertEquals(900,service.login(login,"correct",remote).retryAfter());assertEquals(5,attempts());
        clock.advance(Duration.ofMinutes(15));fail(5);assertEquals(10,attempts());assertEquals(3600,service.login(login,"correct",remote).retryAfter());
        clock.advance(Duration.ofMinutes(60));fail(5);assertEquals(15,attempts());assertTrue(service.login(login,"correct",remote).protectedLogin());
        assertEquals("SESSION_PROTECTED",life.invalidReason(old,user));clock.advance(Duration.ofHours(25));assertTrue(service.login(login,"correct",remote).protectedLogin());assertEquals(15,attempts());
        jdbc.update("UPDATE security_login_attempt SET frozen=0,attempts=0,failure_stage=0,blocked_until=NULL,protection_version=protection_version+1,window_start=? WHERE scope='ACCOUNT' AND key_hash=?",LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Tokyo")),key);
        assertFalse(protection.valid(user.getId(),first.protectionVersion()));var fresh=service.login(login,"correct",remote);assertTrue(protection.valid(user.getId(),fresh.protectionVersion()));assertEquals(0,attempts());
    }
    @Test void fullSuccessClearsCountersAndObservationExpiresWithoutShorteningBlock(){
        fail(4);assertEquals(4,attempts());service.login(login,"correct",remote);assertEquals(0,attempts());
        fail(5);clock.advance(Duration.ofHours(24));service.login(login,"wrong",remote);assertEquals(1,attempts());
    }
    @Test void parallelFailuresAreSerializedAndAuditOutageRollsBackProtection()throws Exception{
        try(var executor=Executors.newFixedThreadPool(5)){var jobs=new ArrayList<Callable<LoginProtectionService.Outcome>>();for(int i=0;i<5;i++)jobs.add(()->service.login(login,"wrong",remote));for(var f:executor.invokeAll(jobs))f.get(30,TimeUnit.SECONDS);}
        assertEquals(5,attempts());clock.advance(Duration.ofMinutes(15));fail(5);clock.advance(Duration.ofMinutes(60));fail(4);assertEquals(14,attempts());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic audit outage")).when(audit).record(eq("LOGIN_PROTECTED"),any(),any(),any(),any(),any(),any());
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->service.login(login,"wrong",remote));assertEquals(14,attempts());assertFalse(protection.state(user.getId(),false).protectedLogin());assertEquals(0,protection.state(user.getId(),false).version());
    }

    private LocalDateTime japaneseNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo"));
    }

    private void futureAccount(int failures, int stage, boolean frozen, LocalDateTime blockedUntil) {
        int protectedValue = 0;
        if (frozen) {
            protectedValue = 1;
        }
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,attempts,window_start,failure_stage,frozen,blocked_until) "
                + "VALUES('ACCOUNT',?,?,?,?,?,?)", key, failures, japaneseNow().plusHours(1), stage, protectedValue, blockedUntil);
    }

    @Test
    void legacyFutureWindowAcceptsCorrectPasswordAndStoresLocalDateTime() {
        futureAccount(4, 0, false, null);
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,attempts,window_start) VALUES('SOURCE',?,?,?)",
                SecurityRequestContext.hash(remote), 2, japaneseNow().plusHours(1));

        LoginProtectionService.Outcome result = service.login(login, "correct", remote);
        assertNotNull(result.user());
        assertEquals(0, attempts());
        LocalDateTime stored = jdbc.queryForObject(
                "SELECT window_start FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",
                LocalDateTime.class, key);
        assertEquals(japaneseNow(), stored);
        assertEquals(3, jdbc.queryForObject(
                "SELECT attempts FROM security_login_attempt WHERE scope='SOURCE' AND key_hash=?",
                Integer.class, SecurityRequestContext.hash(remote)));
    }

    @Test
    void legacyFutureWindowDoesNotEraseFailuresAndFifthFailureStillBlocks() {
        futureAccount(4, 0, false, null);
        LoginProtectionService.Outcome fifth = service.login(login, "wrong", remote);
        assertEquals(5, attempts());
        assertEquals(900, fifth.retryAfter());
        LoginProtectionService.Outcome blocked = service.login(login, "correct", remote);
        assertNull(blocked.user());
        assertEquals(900, blocked.retryAfter());
        assertEquals(5, attempts());
    }

    @Test
    void legacyFutureWindowNeverReleasesProtectedOrBlockedAccounts() {
        futureAccount(15, 3, true, null);
        assertTrue(service.login(login, "correct", remote).protectedLogin());
        assertEquals(15, attempts());
        verify(users, never()).findLoginUser(anyString(), anyString());

        jdbc.update("UPDATE security_login_attempt SET frozen=0,attempts=10,failure_stage=2,blocked_until=? "
                + "WHERE scope='ACCOUNT' AND key_hash=?", japaneseNow().plusHours(1), key);
        LoginProtectionService.Outcome blocked = service.login(login, "correct", remote);
        assertEquals(3600, blocked.retryAfter());
        assertEquals(10, attempts());
        verify(users, never()).findLoginUser(anyString(), anyString());
    }
}
