package com.example.control;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

/** 専用MySQLの限定ユーザーと合成ID。実社員・実管理IDには接続しない。 */
@EnabledIfEnvironmentVariable(named="OA_ACCEPTANCE_TEST",matches="true")
class IsolatedControlStorageTest {
    static class TestClock extends Clock {
        Instant time=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time;}
        void next(){time=time.plusSeconds(60);}
    }
    HikariDataSource source;
    JdbcTemplate jdbc;
    JdbcTransactionManager manager;
    ConsoleStore store;
    OperatorSettings settings;
    TestClock clock;
    String sourceKey;
    final String password="Test-password-123!";
    @BeforeEach void setup() throws Exception {
        source=IsolatedConsoleDatabase.source(3);jdbc=new JdbcTemplate(source);manager=new JdbcTransactionManager(source);clock=new TestClock();
        try(var connection=source.getConnection()){
            IsolatedConsoleDatabase.require(connection);
        }
        settings=new OperatorSettings();settings.setOperatorId(UUID.randomUUID().toString());settings.setLoginId("test_"+UUID.randomUUID().toString().substring(0,8));
        settings.setPasswordHash(OperatorCrypto.encoder().encode(password));settings.setTotpSecret(Totp.encode(new byte[20]));
        store=new ConsoleStore(jdbc,settings,clock,manager);store.register();
        sourceKey=OperatorCrypto.hash("console-test-"+UUID.randomUUID());
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,window_start) VALUES('SOURCE',?,?)",sourceKey,Timestamp.valueOf(store.now()));
        ConsoleRequestContext.begin("synthetic-test-source");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ConsoleIdentity(settings.getOperatorId(),settings.getLoginId()),null,List.of()));
    }
    @AfterEach void retire() {
        try{if(jdbc!=null&&settings!=null){jdbc.update("UPDATE security_console_operator SET active=0,delete_flag=1,deleted_at=CURRENT_TIMESTAMP(6) WHERE id=?",settings.getOperatorId());if(sourceKey!=null)jdbc.update("UPDATE security_login_attempt SET blocked_until=NULL WHERE scope='SOURCE' AND key_hash=?",sourceKey);}}
        finally{SecurityContextHolder.clearContext();ConsoleRequestContext.clear();if(source!=null)source.close();}
    }
    String otp(){return Totp.code(settings.getTotpSecret(),clock.instant().getEpochSecond()/30,6);}
    int failures(){return jdbc.queryForObject("SELECT otp_failures FROM security_console_operator WHERE id=?",Integer.class,settings.getOperatorId());}
    @Test void dedicatedUserCannotReadPasswordsOrModifyEmployeesOrEraseAudit() {
        assertThrows(DataAccessException.class,()->jdbc.queryForList("SELECT password FROM sys_user LIMIT 1"));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE sys_user SET username=username WHERE id=-1"));
        var grants=jdbc.queryForList("SHOW GRANTS",String.class);
        assertTrue(grants.stream().noneMatch(s->s.matches("(?i).*\\b(DELETE|DROP|ALTER|ALL PRIVILEGES)\\b.*")));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE security_console_action SET reason=reason WHERE id=-1"));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE security_access_evidence SET producer=producer WHERE correlation_id='00000000-0000-0000-0000-000000000000'"));
    }
    @Test void loginOtpReplayIsRejectedAndPasswordFailuresPersistAcrossStoreRecreation() {
        assertTrue(store.login(settings.getLoginId(),password,otp()).accepted());
        assertFalse(store.login(settings.getLoginId(),password,otp()).accepted());
        for(int i=0;i<4;i++)store.login(settings.getLoginId(),"incorrect",otp());
        clock.next();
        var recreated=new ConsoleStore(jdbc,settings,clock,manager);
        var blocked=recreated.login(settings.getLoginId(),password,otp());
        assertFalse(blocked.accepted());assertEquals(840,blocked.retryAfter());
        clock.time=clock.time.plusSeconds(840);
        assertTrue(recreated.login(settings.getLoginId(),password,otp()).accepted());
    }
    @Test void stepUpFailuresCommitAndExpireWithoutChangingTarget() {
        for(int i=1;i<=5;i++){
            int expected=i==5?429:400;
            var failure=assertThrows(ResponseStatusException.class,()->store.source(sourceKey,15,"試験", "invalid"));
            assertEquals(expected,failure.getStatusCode().value());assertEquals(i,failures());
        }
        assertNull(jdbc.queryForObject("SELECT blocked_until FROM security_login_attempt WHERE scope='SOURCE' AND key_hash=?",Timestamp.class,sourceKey));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->store.source(sourceKey,15,"試験",otp())).getStatusCode().value());
        clock.time=clock.time.plusSeconds(900);
        store.source(sourceKey,15,"合成接続元の制限試験",otp());assertEquals(0,failures());
        assertNotNull(jdbc.queryForObject("SELECT blocked_until FROM security_login_attempt WHERE scope='SOURCE' AND key_hash=?",Timestamp.class,sourceKey));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->store.source(sourceKey,null,"解除試験",otp())).getStatusCode().value());
        clock.next();store.source(sourceKey,null,"合成接続元の解除試験",otp());
        assertNull(jdbc.queryForObject("SELECT blocked_until FROM security_login_attempt WHERE scope='SOURCE' AND key_hash=?",Timestamp.class,sourceKey));
    }
    @Test void actionAndAuditCommitTogetherAndAuditOutageRollsBackTarget() {
        var faulty=spy(jdbc);
        doAnswer(call->{
            if("SOURCE_RESTRICT".equals(call.getArgument(2)))throw new DataAccessResourceFailureException("synthetic audit outage");
            return call.callRealMethod();
        }).when(faulty).update(eq("INSERT INTO security_console_action(operator_id,operation_code,target_type,target_key,result,reason,correlation_id,source_key) VALUES(?,?,?,?,?,?,?,?)"),any(Object[].class));
        var tested=new ConsoleStore(faulty,settings,clock,manager);
        assertThrows(DataAccessResourceFailureException.class,()->tested.source(sourceKey,15,"監査障害試験",otp()));
        assertNull(jdbc.queryForObject("SELECT blocked_until FROM security_login_attempt WHERE scope='SOURCE' AND key_hash=?",Timestamp.class,sourceKey));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM security_console_action WHERE operator_id=? AND operation_code='SOURCE_RESTRICT'",Integer.class,settings.getOperatorId()));
        clock.next();store.source(sourceKey,15,"合成接続元の制限試験",otp());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM security_console_action WHERE operator_id=? AND operation_code='SOURCE_RESTRICT'",Integer.class,settings.getOperatorId()));
    }
    @Test void accountProtectionAndReleaseOnlyUpdateSyntheticLoginKey() {
        // 実社員を保護しないため、存在確認だけ合成対象へ置換する。
        long fixtureId=Long.MAX_VALUE;
        var testJdbc=spy(jdbc);
        doReturn(1).when(testJdbc).queryForObject(eq("SELECT COUNT(id) FROM sys_user WHERE id=?"),eq(Integer.class),eq(fixtureId));
        var tested=new ConsoleStore(testJdbc,settings,clock,manager);
        String key=OperatorCrypto.hash("user:"+fixtureId);
        long employees=jdbc.queryForObject("SELECT COUNT(id) FROM sys_user",Long.class);
        try{
            tested.account(fixtureId,true,"合成アカウントの保護試験",otp());
            assertEquals(1,jdbc.queryForObject("SELECT frozen FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",Integer.class,key));
            clock.next();tested.account(fixtureId,false,"合成アカウントの解除試験",otp());
            assertEquals(0,jdbc.queryForObject("SELECT frozen FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",Integer.class,key));
            assertEquals(employees,jdbc.queryForObject("SELECT COUNT(id) FROM sys_user",Long.class));
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM security_console_action WHERE operator_id=? AND target_key=?",Integer.class,settings.getOperatorId(),key));
        }finally{jdbc.update("UPDATE security_login_attempt SET frozen=0 WHERE scope='ACCOUNT' AND key_hash=?",key);}
    }
    @Test void dedicatedConsoleCanDecryptCompleteIpv6AndNeverReturnsStoredEnvelope()throws Exception{
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/auth/login");
        String ip="2001:db8:1234:5678:abcd:ef01:2345:6789";request.setRemoteAddr(ip);request.setRemotePort(23456);request.addHeader("User-Agent","SyntheticClient/1.0");request.addHeader("X-Forwarded-For","8.8.8.8");
        var lookup=new SourceGeography("missing-city","missing-asn");
        var evidence=IsolatedConsoleDatabase.evidence(jdbc,lookup);
        store.setEvidence(evidence);ConsoleRequestContext.begin(request);String id=ConsoleRequestContext.current().id();
        assertTrue(store.login(settings.getLoginId(),password,otp()).accepted());
        String stored=jdbc.queryForObject("SELECT encrypted_detail FROM security_access_evidence WHERE correlation_id=?",String.class,id);assertFalse(stored.contains(ip));
        var detail=evidence.detail(id);assertEquals(ip,detail.get("ip"));assertEquals("POST",detail.get("method"));assertFalse(detail.toString().contains("8.8.8.8"));assertFalse(detail.containsKey("encrypted_detail"));
        var rows=store.actions(0);var matching=rows.stream().filter(row->id.equals(row.get("correlation_id"))).findFirst().orElseThrow();
        assertEquals(ip,((Map<?,?>)matching.get("access")).get("ip"));assertFalse(matching.containsKey("encrypted_detail"));
        assertEquals("未取得（旧記録）",evidence.detail(UUID.randomUUID().toString()).get("status"));lookup.close();
    }
}
