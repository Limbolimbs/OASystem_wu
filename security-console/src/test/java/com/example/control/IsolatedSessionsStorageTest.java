package com.example.control;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.security.SessionRegistry;
import com.zaxxer.hikari.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 合成管理範囲だけで会話終了と監査の整合性を確認。実社員・実管理IDは変更しない。 */
@EnabledIfEnvironmentVariable(named="OA_ACCEPTANCE_TEST",matches="true")
class IsolatedSessionsStorageTest {
    HikariDataSource source;JdbcTemplate jdbc;JdbcTransactionManager manager;
    OperatorSettings owner;MemberSecrets secrets;ConsoleMembers members;ConsoleStore store;
    ConsoleEvidence evidence;SessionRegistry registry;ConsoleSessions sessions;IsolatedControlStorageTest.TestClock clock;
    ConsoleIdentity ownerIdentity;String ownerRef,node;final List<String> namespaces=new ArrayList<>();
    @BeforeEach void setup()throws Exception{
        source=IsolatedConsoleDatabase.source(3);jdbc=new JdbcTemplate(source);manager=new JdbcTransactionManager(source);clock=new IsolatedControlStorageTest.TestClock();
        try(var connection=source.getConnection()){IsolatedConsoleDatabase.require(connection);}
        owner=settings();namespaces.add(owner.getOperatorId());secrets=new MemberSecrets(Base64.getEncoder().encodeToString(new byte[32]));owner.setTotpSecret(secrets.totp());evidence=mock(ConsoleEvidence.class);
        members=new ConsoleMembers(jdbc,owner,clock,secrets,evidence,manager);members.registerOwner();node="qa-session-"+UUID.randomUUID();registry=createRegistry(owner,node);registry.initialize();members.setRegistry(registry);
        sessions=new ConsoleSessions(jdbc,owner,members,evidence,registry,manager);store=new ConsoleStore(jdbc,owner,clock,manager);store.setMembers(members);store.setRegistry(registry);store.setSessionOperations(sessions);
        ownerIdentity=new ConsoleIdentity(owner.getOperatorId(),owner.getLoginId());ownerRef=open(ownerIdentity);as(ownerIdentity,ownerRef);
    }
    OperatorSettings settings(){var value=new OperatorSettings();value.setOperatorId(UUID.randomUUID().toString());value.setLoginId("qa14_"+UUID.randomUUID().toString().substring(0,8));value.setPasswordHash(OperatorCrypto.encoder().encode("Synthetic-password-123!"));value.setTotpSecret(Totp.encode(new byte[20]));return value;}
    SessionRegistry createRegistry(OperatorSettings value,String node){return new SessionRegistry(jdbc,manager,clock,"CONTROL",value.getOperatorId(),node,Duration.ofMinutes(10),Duration.ofHours(1));}
    String open(ConsoleIdentity actor){return registry.open(actor.operatorId(),actor.loginId(),actor.role(),actor.version(),0,UUID.randomUUID().toString(),"2001:db8::1234");}
    SessionRegistry.Binding binding(ConsoleIdentity actor,String ref){return new SessionRegistry.Binding(ref,actor.operatorId(),actor.role(),actor.version(),0);}
    void as(ConsoleIdentity actor,String ref){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of()));ConsoleRequestContext.begin("qa14-source-"+owner.getOperatorId());registry.bind(binding(actor,ref));}
    String otp(ConsoleIdentity actor){clock.next();String secret=actor.operatorId().equals(owner.getOperatorId())?owner.getTotpSecret():secrets.decrypt(owner.getOperatorId(),actor.operatorId(),jdbc.queryForObject("SELECT totp_secret_enc FROM security_console_operator WHERE id=?",String.class,actor.operatorId()));return Totp.code(secret,clock.instant().getEpochSecond()/30,6);}
    ConsoleIdentity staff(String role){String id=UUID.randomUUID().toString(),login="qa14_"+UUID.randomUUID().toString().substring(0,8),secret=secrets.totp();jdbc.update("INSERT INTO security_console_operator(id,login_id,owner_id,role,display_name,active,enrollment_state,password_hash,totp_secret_enc) VALUES(?,?,?,?,?,1,'ACTIVE',?,?)",id,login,owner.getOperatorId(),role,"合成会話試験",owner.getPasswordHash(),secrets.encrypt(owner.getOperatorId(),id,secret));return new ConsoleIdentity(id,login,role,0);}
    long version(String ref){return jdbc.queryForObject("SELECT version FROM security_session_registry WHERE ref_id=?",Long.class,ref);}
    @AfterEach void retire(){try{if(jdbc!=null)for(String namespace:namespaces){jdbc.update("UPDATE security_session_registry SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP(6)),revocation_reason=COALESCE(revocation_reason,'TEST_END') WHERE realm='CONTROL' AND owner_key=?",namespace);jdbc.update("UPDATE security_console_operator SET active=0,delete_flag=1,deleted_at=CURRENT_TIMESTAMP(6),invitation_hash=NULL,password_hash=NULL,totp_secret_enc=NULL,auth_version=auth_version+1 WHERE owner_id=?",namespace);}}finally{if(registry!=null)registry.clear();SecurityContextHolder.clearContext();ConsoleRequestContext.clear();if(source!=null)source.close();}}
    @Test void selectiveEndKeepsOtherSessionsAndCredentialsUnchanged(){
        var actor=staff("OPERATOR");String first=open(actor),second=open(actor);var before=jdbc.queryForMap("SELECT password_hash,totp_secret_enc,auth_version,active,role FROM security_console_operator WHERE id=?",actor.operatorId());
        var result=sessions.terminate(first,0,false,"合成会話の選択終了",otp(ownerIdentity));assertEquals(1,result.get("ended"));assertEquals(false,result.get("selfEnded"));assertFalse(registry.valid(binding(actor,first),false));assertTrue(registry.valid(binding(actor,second),false));
        assertEquals(before,jdbc.queryForMap("SELECT password_hash,totp_secret_enc,auth_version,active,role FROM security_console_operator WHERE id=?",actor.operatorId()));String fresh=open(actor);assertTrue(registry.valid(binding(actor,fresh),false));
        new TransactionTemplate(manager).executeWithoutResult(status->{registry.bind(binding(actor,first));assertEquals(401,assertThrows(ResponseStatusException.class,registry::requireCurrent).getStatusCode().value());});
    }
    @Test void allEndUsesSnapshotAndDoesNotPreventLaterLogin(){
        var actor=staff("OPERATOR");String first=open(actor),second=open(actor);var result=sessions.terminate(first,0,true,"本人の確認時点の全会話を終了",otp(ownerIdentity));assertEquals(2,result.get("ended"));assertFalse(registry.valid(binding(actor,first),false));assertFalse(registry.valid(binding(actor,second),false));assertTrue(registry.valid(binding(actor,open(actor)),false));
    }
    @Test void repeatedBulkEndDoesNotExpandAnEndedAnchorToLaterSessions(){
        var actor=staff("OPERATOR");String first=open(actor);
        sessions.terminate(first,0,true,"確認時点の合成会話を終了",otp(ownerIdentity));
        String later=open(actor);
        var repeated=sessions.terminate(first,0,true,"旧画面からの再操作",otp(ownerIdentity));
        assertEquals(0,repeated.get("ended"));assertTrue(registry.valid(binding(actor,later),false));
        assertEquals(1,version(first));
    }
    @Test void roleBoundariesAndOwnerReviewLeaveLaterSessionsValid(){
        var actor=staff("OPERATOR");String actorRef=open(actor);as(actor,actorRef);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->sessions.terminate(ownerRef,0,false,"権限境界の試験","000000")).getStatusCode().value());
        sessions.request(ownerRef,0,true,"最高管理者の既存会話終了を申請",otp(actor));var review=store.reviews().getFirst();assertEquals(owner.getLoginId(),review.get("target_login"));assertEquals("CONTROL",review.get("target_realm"));
        String later=open(ownerIdentity);as(ownerIdentity,later);store.review(((Number)review.get("id")).longValue(),true,"本人確認済みの合成対象だけを承認",otp(ownerIdentity));
        assertFalse(registry.valid(binding(ownerIdentity,ownerRef),false));assertTrue(registry.valid(binding(ownerIdentity,later),false));assertEquals("APPROVED",store.reviews().getFirst().get("state"));
        var observer=staff("OBSERVER");String observerRef=open(observer);as(observer,observerRef);assertEquals(403,assertThrows(ResponseStatusException.class,()->sessions.request(later,0,false,"監視専用の権限境界","000000")).getStatusCode().value());
        assertEquals(true,sessions.terminate(observerRef,0,false,"本人の管理会話を終了",otp(observer)).get("selfEnded"));
    }
    @Test void staleVersionAndOtherManagementNamespaceAreRejected(){
        var actor=staff("OPERATOR");String ref=open(actor);assertEquals(409,assertThrows(ResponseStatusException.class,()->sessions.terminate(ref,7,false,"更新競合の試験",otp(ownerIdentity))).getStatusCode().value());assertTrue(registry.valid(binding(actor,ref),false));
        var other=settings();namespaces.add(other.getOperatorId());new ConsoleMembers(jdbc,other,clock,secrets,evidence,manager).registerOwner();var isolated=createRegistry(other,"other-"+UUID.randomUUID());isolated.initialize();String foreign=isolated.open(other.getOperatorId(),other.getLoginId(),"OWNER",0,0,null,"127.0.0.1");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->sessions.terminate(foreign,0,false,"別管理範囲は操作不可","000000")).getStatusCode().value());assertFalse(sessions.list("CONTROL","ALL",other.getLoginId(),"",0).toString().contains(foreign));
    }
    @Test void auditFailureRollsBackSessionTerminationButConsumesOtp(){
        var actor=staff("OPERATOR");String ref=open(actor);var faulty=spy(jdbc);
        doAnswer(call->{if("SESSION_REVOKE".equals(call.getArgument(2)))throw new DataAccessResourceFailureException("synthetic audit outage");return call.callRealMethod();}).when(faulty).update(eq("INSERT INTO security_console_action(operator_id,operation_code,target_type,target_key,result,reason,correlation_id,source_key) VALUES(?,?,?,?,?,?,?,?)"),any(Object[].class));
        var testedMembers=new ConsoleMembers(faulty,owner,clock,secrets,evidence,manager);testedMembers.setRegistry(registry);var tested=new ConsoleSessions(jdbc,owner,testedMembers,evidence,registry,manager);String code=otp(ownerIdentity);
        assertThrows(DataAccessResourceFailureException.class,()->tested.terminate(ref,0,false,"監査記録障害の試験",code));assertTrue(registry.valid(binding(actor,ref),false));assertEquals(0,version(ref));assertEquals(400,assertThrows(ResponseStatusException.class,()->sessions.terminate(ref,0,false,"使用済みコードは再送不可",code)).getStatusCode().value());
    }
    @Test void expiredOrEndedSessionCannotBeRevivedAndRestartIsNodeScoped(){
        var actor=staff("OPERATOR");String ref=open(actor);clock.time=clock.time.plusSeconds(601);assertFalse(registry.valid(binding(actor,ref),false));assertFalse(registry.touch(ref));
        String fresh=open(actor);clock.time=clock.time.plusSeconds(300);assertTrue(registry.touch(fresh));var row=jdbc.queryForMap("SELECT idle_expires_at,absolute_expires_at FROM security_session_registry WHERE ref_id=?",fresh);assertFalse(SessionRegistry.time(row.get("idle_expires_at")).isAfter(SessionRegistry.time(row.get("absolute_expires_at"))));
        var separate=createRegistry(owner,node+"-separate");separate.initialize();String other=separate.open(actor.operatorId(),actor.loginId(),actor.role(),0,0,null,"127.0.0.1");assertTrue(registry.valid(binding(actor,fresh),false));createRegistry(owner,node).initialize();assertFalse(registry.valid(binding(actor,fresh),false));assertTrue(separate.valid(binding(actor,other),false));
        assertFalse(registry.touch(fresh));
    }
    @Test void sequenceGuardSerializesBulkEndAndConcurrentNewSession()throws Exception{
        var actor=staff("OPERATOR");String old=open(actor);var started=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{
            var future=new TransactionTemplate(manager).execute(status->{long cutoff=registry.lockSubject("CONTROL",owner.getOperatorId(),actor.operatorId());var pending=pool.submit(()->{started.countDown();return open(actor);});try{assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->pending.get(150,TimeUnit.MILLISECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                jdbc.update("UPDATE security_session_registry SET revoked_at=?,revocation_reason='TEST_SNAPSHOT',version=version+1 WHERE realm='CONTROL' AND owner_key=? AND subject_key=? AND sequence_no<=? AND revoked_at IS NULL",Timestamp.valueOf(registry.now()),owner.getOperatorId(),actor.operatorId(),cutoff);return pending;});
            String later=future.get(10,TimeUnit.SECONDS);assertFalse(registry.valid(binding(actor,old),false));assertTrue(registry.valid(binding(actor,later),false));
        }finally{pool.shutdownNow();}
    }
    @Test void listUsesCanonicalExactIpAndNeverExposesAuthenticationIdentifiers(){
        var actor=staff("OPERATOR");String ref=open(actor);var result=sessions.list("CONTROL","ACTIVE",actor.loginId(),"2001:0db8:0:0:0:0:0:1234",0);var row=((List<Map<String,Object>>)result.get("items")).getFirst();assertEquals(ref,row.get("ref_id"));
        for(String secret:List.of("owner_key","subject_key","runtime_id","node_key","ip_key","password_hash","totp_secret_enc","session_id","cookie"))assertFalse(row.containsKey(secret));
        assertEquals(0L,sessions.list("CONTROL","ACTIVE",actor.loginId(),"2001:db8::5678",0).get("total"));assertEquals(400,assertThrows(ResponseStatusException.class,()->sessions.list("CONTROL","ACTIVE","","not-an-ip",0)).getStatusCode().value());
    }
}
