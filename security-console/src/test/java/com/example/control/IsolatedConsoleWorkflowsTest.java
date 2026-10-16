package com.example.control;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.zaxxer.hikari.*;
import com.example.security.SessionRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** 本番設定を一切参照しない専用MySQLで、実暗号・実TX・実ロールを検証する。 */
@EnabledIfEnvironmentVariable(named="OA_ACCEPTANCE_TEST",matches="true")
class IsolatedConsoleWorkflowsTest {
    static final String URL="jdbc:mysql://127.0.0.1:13316/oa_qa_20261015?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Tokyo";
    static final String DB_PASSWORD="QaDatabaseOnly123!",PASSWORD="Member-password-123!";
    static KeyPair pair;
    HikariDataSource source;JdbcTemplate jdbc;JdbcTransactionManager manager;
    ConsoleMembers members;ConsoleStore store;OperatorSettings owner;MemberSecrets secrets;ConsoleEvidence evidence;
    SessionRegistry registry;ConsoleSessions sessions;MutableClock clock;
    static final class MutableClock extends Clock {
        Instant value=Instant.parse("2026-10-15T12:00:00.123456789Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
        void next(){value=value.plusSeconds(60);}
    }
    @BeforeAll static void keys() throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);pair=generator.generateKeyPair();}
    @BeforeEach void setup() throws Exception {
        try(var connection=DriverManager.getConnection(URL,"root",DB_PASSWORD);var sql=connection.createStatement();var result=sql.executeQuery("SELECT @@port,@@datadir,DATABASE(),(SELECT purpose FROM qa_environment_marker WHERE id=1)")){
            assertTrue(result.next());assertEquals(13316,result.getInt(1));
            assertEquals("d:/oa-maintenance/business-acceptance-20261015/mysql-data/",result.getString(2).replace('\\','/').toLowerCase(Locale.ROOT));
            assertEquals("oa_qa_20261015",result.getString(3));assertEquals("isolated business acceptance only",result.getString(4));
        }
        var config=new HikariConfig();config.setJdbcUrl(URL);config.setUsername("root");config.setPassword(DB_PASSWORD);config.setMaximumPoolSize(4);config.setConnectionInitSql("SET time_zone='+09:00'");
        source=new HikariDataSource(config);jdbc=new JdbcTemplate(source);manager=new JdbcTransactionManager(source);clock=new MutableClock();
        owner=new OperatorSettings();owner.setOperatorId(UUID.randomUUID().toString());owner.setLoginId("qa_"+UUID.randomUUID().toString().substring(0,8));owner.setPasswordHash(OperatorCrypto.encoder().encode(PASSWORD));
        byte[] master=new byte[32];new SecureRandom().nextBytes(master);secrets=new MemberSecrets(Base64.getEncoder().encodeToString(master));owner.setTotpSecret(secrets.totp());
        var publicResource=new ByteArrayResource(Base64.getEncoder().encode(pair.getPublic().getEncoded()));
        evidence=new ConsoleEvidence(jdbc,new SourceGeography("D:/oa-maintenance/business-acceptance-20261015/no-city.mmdb","D:/oa-maintenance/business-acceptance-20261015/no-asn.mmdb"),Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),publicResource);
        members=new ConsoleMembers(jdbc,owner,clock,secrets,evidence,manager);members.registerOwner();store=new ConsoleStore(jdbc,owner,clock,manager);store.setMembers(members);store.setEvidence(evidence);
        registry=new SessionRegistry(jdbc,manager,clock,"CONTROL",owner.getOperatorId(),"isolated-console-qa",Duration.ofMinutes(10),Duration.ofHours(1));registry.initialize();members.setRegistry(registry);store.setRegistry(registry);
        sessions=new ConsoleSessions(jdbc,owner,members,evidence,registry,manager);store.setSessionOperations(sessions);
        asOwner();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();ConsoleRequestContext.clear();if(registry!=null)registry.clear();if(source!=null)source.close();}
    String otp(String secret){return Totp.code(secret,clock.instant().getEpochSecond()/30,6);}
    void auth(ConsoleIdentity identity){
        clock.next();var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.77");request.addHeader("User-Agent","Isolated QA browser");ConsoleRequestContext.begin(request);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+identity.role()))));
        if(members.valid(identity))registry.bind(new SessionRegistry.Binding(registry.open(identity.operatorId(),identity.loginId(),identity.role(),identity.version(),0,ConsoleRequestContext.current().id(),request.getRemoteAddr()),identity.operatorId(),identity.role(),identity.version(),0));
    }
    void asOwner(){auth(new ConsoleIdentity(owner.getOperatorId(),owner.getLoginId()));}
    ConsoleMembers.Registration invite(String role){return members.create("qa_"+UUID.randomUUID().toString().substring(0,8),"隔離試験担当者",role,"担当者登録の隔離試験",otp(owner.getTotpSecret()));}
    String loginId(String id){return jdbc.queryForObject("SELECT login_id FROM security_console_operator WHERE id=?",String.class,id);}
    String seed(String id){return secrets.decrypt(owner.getOperatorId(),id,jdbc.queryForObject("SELECT totp_secret_enc FROM security_console_operator WHERE id=?",String.class,id));}
    void bind(ConsoleMembers.Registration invitation){var setup=members.start(loginId(invitation.id()),invitation.token(),PASSWORD);assertNotNull(setup);assertTrue(members.confirm(setup,otp(seed(invitation.id()))));}
    ConsoleIdentity enabled(String role){var invitation=invite(role);bind(invitation);asOwner();members.change(invitation.id(),"enable",null,null,"本人確認済みの隔離試験",otp(owner.getTotpSecret()));clock.next();var outcome=members.login(loginId(invitation.id()),PASSWORD,otp(seed(invitation.id())));assertTrue(outcome.accepted());return outcome.identity();}
    void forbidden(Runnable operation){assertEquals(403,assertThrows(ResponseStatusException.class,operation::run).getStatusCode().value());}
    @ParameterizedTest @ValueSource(strings={"OWNER","OPERATOR","OBSERVER"})
    void permissionsAreRecheckedAgainstStoredRole(String role){
        ConsoleIdentity actor=role.equals("OWNER")?new ConsoleIdentity(owner.getOperatorId(),owner.getLoginId()):enabled(role);auth(actor);
        assertEquals(actor,members.require("READ",false));assertEquals(actor,members.require("PERSONAL",false));
        if(role.equals("OBSERVER")){forbidden(()->members.require("OPERATE",false));forbidden(()->members.require("REVIEW_REQUEST",false));}
        else{assertEquals(actor,members.require("OPERATE",false));assertEquals(actor,members.require("REVIEW_REQUEST",false));}
        if(!role.equals("OWNER")){forbidden(()->members.list());forbidden(()->members.create("qa_elevation","試験","OPERATOR","不正昇格の拒否",otp(seed(actor.operatorId()))));}
        else assertNotNull(members.list());
        if(!role.equals("OWNER"))forbidden(()->members.require("UNDEFINED_PERMISSION",false));
    }
    @Test void invitationOnceUniqueSeedsAndNoSecretInMemberList(){
        var first=invite("OPERATOR");bind(first);assertNull(members.start(loginId(first.id()),first.token(),PASSWORD));
        assertFalse(members.login(loginId(first.id()),PASSWORD,otp(seed(first.id()))).accepted());
        asOwner();var second=invite("OBSERVER");bind(second);assertNotEquals(seed(first.id()),seed(second.id()));
        asOwner();String listed=members.list().toString();assertFalse(listed.contains(first.token()));assertFalse(listed.contains(seed(first.id())));assertFalse(listed.contains("password_hash"));
    }
    @Test void totpReplayIsRejectedAndFreshStepWorks(){
        var actor=enabled("OPERATOR");String code=otp(seed(actor.operatorId()));assertFalse(members.login(actor.loginId(),PASSWORD,code).accepted());clock.next();assertTrue(members.login(actor.loginId(),PASSWORD,otp(seed(actor.operatorId()))).accepted());
    }
    @Test void roleChangeDisableAndReinviteRevokeOldIdentityAndSeed(){
        var actor=enabled("OPERATOR");String oldSeed=seed(actor.operatorId());asOwner();members.change(actor.operatorId(),"edit","監視担当","OBSERVER","権限変更の試験",otp(owner.getTotpSecret()));assertFalse(members.valid(actor));
        asOwner();members.change(actor.operatorId(),"disable",null,null,"停止試験",otp(owner.getTotpSecret()));assertFalse(members.login(actor.loginId(),PASSWORD,otp(oldSeed)).accepted());
        asOwner();var invitation=members.change(actor.operatorId(),"reinvite",null,null,"再発行試験",otp(owner.getTotpSecret()));assertNull(jdbc.queryForObject("SELECT totp_secret_enc FROM security_console_operator WHERE id=?",String.class,actor.operatorId()));
        bind(invitation);assertNotEquals(oldSeed,seed(actor.operatorId()));assertFalse(members.valid(actor));
    }
    @Test void deletionIsLogicalAndOwnerCannotDeleteSelf(){
        var actor=enabled("OBSERVER");asOwner();members.change(actor.operatorId(),"delete",null,null,"退任者の論理削除試験",otp(owner.getTotpSecret()));
        assertEquals(1,jdbc.queryForObject("SELECT delete_flag FROM security_console_operator WHERE id=?",Integer.class,actor.operatorId()));assertNull(jdbc.queryForObject("SELECT password_hash FROM security_console_operator WHERE id=?",String.class,actor.operatorId()));
        asOwner();assertThrows(ResponseStatusException.class,()->members.change(owner.getOperatorId(),"delete",null,null,"自己削除の拒否",otp(owner.getTotpSecret())));
    }
    @Test void expiredInvitationsAndSetupsCannotBeUsed(){
        var first=invite("OPERATOR");clock.value=clock.value.plusSeconds(86401);assertNull(members.start(loginId(first.id()),first.token(),PASSWORD));
        asOwner();var second=invite("OPERATOR");var setup=members.start(loginId(second.id()),second.token(),PASSWORD);clock.value=clock.value.plusSeconds(601);assertThrows(ResponseStatusException.class,()->members.setup(setup));
    }
    @Test void passwordChangeRevokesAllSessionsAndCannotTargetAnotherMember(){
        var actor=enabled("OBSERVER");auth(actor);String ref=registry.binding().ref();members.password(PASSWORD,"Changed-member123!",otp(seed(actor.operatorId())));assertFalse(members.valid(actor));
        assertFalse(registry.valid(new SessionRegistry.Binding(ref,actor.operatorId(),actor.role(),actor.version()+1,0),false));
        clock.next();assertTrue(members.login(actor.loginId(),"Changed-member123!",otp(seed(actor.operatorId()))).accepted());assertNull(jdbc.queryForObject("SELECT password_hash FROM security_console_operator WHERE id=?",String.class,owner.getOperatorId()));
    }
    @Test void progressiveProtectionReleaseNeverResurrectsOldIdentity(){
        var actor=enabled("OPERATOR");String secret=seed(actor.operatorId());
        for(int tier=0;tier<3;tier++){
            for(int i=0;i<5;i++)assertFalse(members.login(actor.loginId(),"wrong-password","000000").accepted());
            assertEquals((tier+1)*5,jdbc.queryForObject("SELECT failed_attempts FROM security_console_operator WHERE id=?",Integer.class,actor.operatorId()));
            if(tier<2){int before=jdbc.queryForObject("SELECT failed_attempts FROM security_console_operator WHERE id=?",Integer.class,actor.operatorId());assertFalse(members.login(actor.loginId(),PASSWORD,otp(secret)).accepted());assertEquals(before,jdbc.queryForObject("SELECT failed_attempts FROM security_console_operator WHERE id=?",Integer.class,actor.operatorId()));clock.value=clock.value.plusSeconds(tier==0?900:3600);}
        }
        assertFalse(members.valid(actor));clock.value=clock.value.plusSeconds(90000);assertTrue(members.login(actor.loginId(),PASSWORD,otp(secret)).protectedLogin());
        asOwner();members.change(actor.operatorId(),"release",null,null,"本人確認後の保護解除試験",otp(owner.getTotpSecret()));assertFalse(members.valid(actor));clock.next();assertTrue(members.login(actor.loginId(),PASSWORD,otp(secret)).accepted());
    }
    @Test void unknownIdsAndForgedIdentityCannotAffectOwner(){
        for(int i=0;i<5;i++)assertFalse(members.login("unknown_qa","incorrect","000000").accepted());assertEquals(0,jdbc.queryForObject("SELECT failed_attempts FROM security_console_operator WHERE id=?",Integer.class,owner.getOperatorId()));
        assertFalse(members.valid(new ConsoleIdentity(UUID.randomUUID().toString(),owner.getLoginId(),"OWNER",0)));
    }
    @Test void operatorReviewNeedsOwnerAndObserverCannotRequest(){
        var operator=enabled("OPERATOR");auth(operator);String target=OperatorCrypto.hash("isolated-source:"+owner.getOperatorId());
        forbidden(()->store.source(target,null,"直接解除の拒否",otp(seed(operator.operatorId()))));clock.next();store.requestReview("SOURCE_RELEASE",target,null,"解除の承認依頼",otp(seed(operator.operatorId())));
        asOwner();var review=store.reviews().getFirst();store.review(((Number)review.get("id")).longValue(),false,"対象確認による却下",otp(owner.getTotpSecret()));assertEquals("REJECTED",store.reviews().getFirst().get("state"));
    }
    @Test void auditFailureRollsBackInvitation(){
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM security_console_operator WHERE owner_id=?",Integer.class,owner.getOperatorId());
        jdbc.execute("RENAME TABLE security_console_action TO qa_unavailable_console_action");
        try{assertThrows(org.springframework.dao.DataAccessException.class,()->invite("OPERATOR"));assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM security_console_operator WHERE owner_id=?",Integer.class,owner.getOperatorId()));}
        finally{jdbc.execute("RENAME TABLE qa_unavailable_console_action TO security_console_action");}
    }
    @ParameterizedTest @ValueSource(longs={999999,-1,0})
    void invalidOrMissingProtectionTargetIsClientErrorBeforeOtp(long id){
        var actor=enabled("OPERATOR");auth(actor);
        long before=jdbc.queryForObject("SELECT last_totp_step FROM security_console_operator WHERE id=?",Long.class,actor.operatorId());
        int expected=id>0?404:400;
        assertEquals(expected,assertThrows(ResponseStatusException.class,()->store.account(id,true,"対象IDの検証試験",otp(seed(actor.operatorId())))).getStatusCode().value());
        assertEquals(before,jdbc.queryForObject("SELECT last_totp_step FROM security_console_operator WHERE id=?",Long.class,actor.operatorId()));
    }
    @ParameterizedTest @ValueSource(strings={"9223372036854775808","9999999999999999999"})
    void accountReviewRejectsLongOverflowBeforeOtpAndInsert(String target){
        var actor=enabled("OPERATOR");auth(actor);
        long before=jdbc.queryForObject("SELECT last_totp_step FROM security_console_operator WHERE id=?",Long.class,actor.operatorId());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->store.requestReview("ACCOUNT_PROTECT",target,null,"ID上限の検証試験",otp(seed(actor.operatorId())))).getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM security_console_review WHERE owner_id=?",Integer.class,owner.getOperatorId()));
        assertEquals(before,jdbc.queryForObject("SELECT last_totp_step FROM security_console_operator WHERE id=?",Long.class,actor.operatorId()));
    }
    @Test void rawIpIsEncryptedAtRestAndCanBeViewedWithoutSecrets(){
        invite("OPERATOR");var context=ConsoleRequestContext.current();String encrypted=jdbc.queryForObject("SELECT encrypted_detail FROM security_access_evidence WHERE correlation_id=?",String.class,context.id());assertFalse(encrypted.contains("192.0.2.77"));
        var detail=evidence.detail(context.id());assertEquals("192.0.2.77",detail.get("ip"));assertFalse(detail.toString().contains(PASSWORD));assertFalse(detail.toString().contains(owner.getTotpSecret()));
    }
    @Test void subMicrosecondClockWorksForLoginAndImmediateSessionValidation(){
        assertFalse(members.login(owner.getLoginId(),"wrong-password","000000").accepted());assertFalse(members.login(owner.getLoginId(),"wrong-password","000000").accepted());
        assertEquals(2,jdbc.queryForObject("SELECT failed_attempts FROM security_console_operator WHERE id=?",Integer.class,owner.getOperatorId()));
        var binding=registry.binding();assertTrue(registry.valid(binding,false));clock.value=clock.value.plusSeconds(600);assertFalse(registry.valid(binding,false));
    }
    SessionRegistry qaOaRegistry(){return new SessionRegistry(jdbc,manager,clock,"OA",SessionRegistry.OA_OWNER,"isolated-console-business-qa",Duration.ofMinutes(30),Duration.ofHours(8));}
    String businessSession(SessionRegistry oa,long employee,String role){
        String key=OperatorCrypto.hash("user:"+employee);jdbc.update("UPDATE security_login_attempt SET frozen=0,blocked_until=NULL WHERE scope='ACCOUNT' AND key_hash=?",key);
        long auth=jdbc.queryForObject("SELECT authentication_version FROM sys_user WHERE id=?",Long.class,employee);var protection=jdbc.queryForList("SELECT protection_version FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",key);long version=protection.isEmpty()?0:((Number)protection.getFirst().get("protection_version")).longValue();
        return oa.open(Long.toString(employee),"QA_EMP_"+(employee-91000),role,auth,version,null,"192.0.2.91");
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","OPERATOR","OBSERVER"})
    void realSessionTerminationEnforcesTargetRoleAndSelfBoundary(String role){
        ConsoleIdentity actor=role.equals("OWNER")?new ConsoleIdentity(owner.getOperatorId(),owner.getLoginId()):enabled(role);auth(actor);
        var oa=qaOaRegistry();String employee=businessSession(oa,91004,"EMPLOYEE"),admin=businessSession(oa,91001,"ADMIN");String self=registry.binding().ref();
        if(role.equals("OBSERVER"))forbidden(()->sessions.terminate(employee,0,false,"対象外操作の拒否",otp(seed(actor.operatorId()))));
        else{assertEquals(1,sessions.terminate(employee,0,false,"一般社員会話の終了試験",otp(role.equals("OWNER")?owner.getTotpSecret():seed(actor.operatorId()))).get("ended"));clock.next();}
        if(!role.equals("OWNER"))forbidden(()->sessions.terminate(admin,0,false,"管理権限対象の拒否",otp(seed(actor.operatorId()))));
        else{assertEquals(1,sessions.terminate(admin,0,false,"管理者会話の終了試験",otp(owner.getTotpSecret())).get("ended"));clock.next();}
        assertEquals(true,sessions.terminate(self,0,false,"本人会話の終了試験",otp(role.equals("OWNER")?owner.getTotpSecret():seed(actor.operatorId()))).get("selfEnded"));assertFalse(registry.valid(registry.binding(),false));
    }
    @Test void approvedAllSessionRequestNeverRevokesLaterLogin(){
        var operator=enabled("OPERATOR");auth(operator);var oa=qaOaRegistry();String first=businessSession(oa,91001,"ADMIN");sessions.request(first,0,true,"高権限対象の全会話終了申請",otp(seed(operator.operatorId())));long id=((Number)store.reviews().getFirst().get("id")).longValue();
        clock.next();String later=businessSession(oa,91001,"ADMIN");asOwner();store.review(id,true,"対象と申請時範囲の確認",otp(owner.getTotpSecret()));
        assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM security_session_registry WHERE ref_id=?",Timestamp.class,first));assertNull(jdbc.queryForObject("SELECT revoked_at FROM security_session_registry WHERE ref_id=?",Timestamp.class,later));
        clock.next();assertEquals(0,sessions.terminate(first,0,true,"終了済み参照の再送試験",otp(owner.getTotpSecret())).get("ended"));assertNull(jdbc.queryForObject("SELECT revoked_at FROM security_session_registry WHERE ref_id=?",Timestamp.class,later));
    }
    @Test void accountProtectionAndSourceReviewKeepBusinessCredentialsUntouched(){
        var operator=enabled("OPERATOR");auth(operator);String hash=jdbc.queryForObject("SELECT password FROM sys_user WHERE id=91004",String.class);String target=OperatorCrypto.hash("user:91004");
        store.account(91004,true,"本人確認が必要な試験保護",otp(seed(operator.operatorId())));assertEquals(1,jdbc.queryForObject("SELECT frozen FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",Integer.class,target));clock.next();
        forbidden(()->store.account(91004,false,"運用担当の直接解除拒否",otp(seed(operator.operatorId()))));forbidden(()->store.account(91001,true,"管理者保護は審査対象",otp(seed(operator.operatorId()))));
        store.requestReview("ACCOUNT_RELEASE","91004",null,"本人確認後の解除申請",otp(seed(operator.operatorId())));asOwner();store.review(((Number)store.reviews().getFirst().get("id")).longValue(),true,"解除承認の試験",otp(owner.getTotpSecret()));
        assertEquals(0,jdbc.queryForObject("SELECT frozen FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",Integer.class,target));assertEquals(hash,jdbc.queryForObject("SELECT password FROM sys_user WHERE id=91004",String.class));assertEquals("EMPLOYEE",jdbc.queryForObject("SELECT role FROM sys_user WHERE id=91004",String.class));
        String from=OperatorCrypto.hash("isolated-restrict-source:"+owner.getOperatorId());jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,window_start) VALUES('SOURCE',?,?)",from,Timestamp.valueOf(members.now()));auth(operator);store.source(from,60,"接続元の試験制限",otp(seed(operator.operatorId())));clock.next();forbidden(()->store.source(from,15,"既存制限の短縮拒否",otp(seed(operator.operatorId()))));clock.next();
        store.requestReview("SOURCE_RELEASE",from,null,"接続元解除の申請",otp(seed(operator.operatorId())));asOwner();store.review(((Number)store.reviews().getFirst().get("id")).longValue(),true,"接続元解除の承認",otp(owner.getTotpSecret()));assertNull(jdbc.queryForObject("SELECT blocked_until FROM security_login_attempt WHERE scope='SOURCE' AND key_hash=?",Timestamp.class,from));
    }
}
