package com.example.control;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ConsoleStore {
    public record LoginResult(boolean accepted, long retryAfter, ConsoleIdentity identity,boolean protectedLogin) {
        public LoginResult(boolean accepted,long retryAfter){this(accepted,retryAfter,null,false);}
        public LoginResult(boolean accepted,long retryAfter,ConsoleIdentity identity){this(accepted,retryAfter,identity,false);}
    }
    private final JdbcTemplate jdbc;
    private final OperatorSettings settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private ConsoleEvidence evidence;
    private ConsoleMembers members;
    private com.example.security.SessionRegistry registry;
    private ConsoleSessions sessionOperations;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder = OperatorCrypto.encoder();
    public ConsoleStore(JdbcTemplate jdbc, OperatorSettings settings, Clock clock, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.settings=settings; this.clock=clock; transaction=new TransactionTemplate(manager);
    }
    @org.springframework.beans.factory.annotation.Autowired public void setEvidence(ConsoleEvidence value){evidence=value;}
    @org.springframework.beans.factory.annotation.Autowired public void setMembers(ConsoleMembers value){members=value;}
    @org.springframework.beans.factory.annotation.Autowired public void setRegistry(com.example.security.SessionRegistry value){registry=value;}
    @org.springframework.beans.factory.annotation.Autowired public void setSessionOperations(ConsoleSessions value){sessionOperations=value;}
    public void startSession(jakarta.servlet.http.HttpSession session,ConsoleIdentity identity){
        if(registry==null)return;var context=ConsoleRequestContext.current();
        String ref=registry.open(identity.operatorId(),identity.loginId(),identity.role(),identity.version(),0,context.id(),context.details()==null?null:context.details().get("ip"));
        session.setAttribute(com.example.security.SessionRegistry.REF,ref);
    }
    private com.example.security.SessionRegistry.Binding sessionBinding(jakarta.servlet.http.HttpSession session,ConsoleIdentity identity){
        return session!=null && session.getAttribute(com.example.security.SessionRegistry.REF) instanceof String ref?new com.example.security.SessionRegistry.Binding(ref,identity.operatorId(),identity.role(),identity.version(),0):null;
    }
    public boolean sessionValid(jakarta.servlet.http.HttpSession session,ConsoleIdentity identity){return registry==null||registry.valid(sessionBinding(session,identity),false);}
    public void bindSession(jakarta.servlet.http.HttpSession session,ConsoleIdentity identity){if(registry!=null)registry.bind(sessionBinding(session,identity));}
    public void clearSession(){if(registry!=null)registry.clear();}
    public void endSession(jakarta.servlet.http.HttpSession session){if(registry!=null && session!=null && session.getAttribute(com.example.security.SessionRegistry.REF) instanceof String ref)registry.close(ref,"AUTH_CHANGED");}
    public boolean touchSession(jakarta.servlet.http.HttpSession session){return registry==null || session!=null && session.getAttribute(com.example.security.SessionRegistry.REF) instanceof String ref && registry.touch(ref);}
    public boolean valid(ConsoleIdentity identity){return members==null?identity.operatorId().equals(settings.getOperatorId()) && jdbc.queryForObject("SELECT COUNT(*) FROM security_console_operator WHERE id=? AND active=1 AND delete_flag=0 AND login_protected=0 AND auth_version=?",Integer.class,settings.getOperatorId(),identity.version())==1:members.valid(identity);}
    LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Tokyo")).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private LocalDateTime storedTime(Object value) {
        if(value==null)return null;
        if(value instanceof LocalDateTime time)return time;
        if(value instanceof Timestamp time)return time.toLocalDateTime();
        throw new IllegalStateException("保存日時の形式が不正です。");
    }
    @Transactional public void register() {
        if(members!=null){members.registerOwner();return;}
        jdbc.update("INSERT INTO security_console_operator(id,login_id) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id",settings.getOperatorId(),settings.getLoginId());
        if (!settings.getLoginId().equals(jdbc.queryForObject("SELECT login_id FROM security_console_operator WHERE id=?",String.class,settings.getOperatorId()))) throw new IllegalStateException("管理IDの登録が一致しません。");
    }
    private Map<String,Object> operator() {
        if(members!=null){var actor=members.require("OPERATE",true);return members.row(actor.operatorId(),true);}
        return jdbc.queryForMap("SELECT * FROM security_console_operator WHERE id=? FOR UPDATE",settings.getOperatorId());
    }
    private boolean active(Map<String,Object> row) { return ((Number)row.get("active")).intValue()==1 && ((Number)row.get("delete_flag")).intValue()==0; }
    public boolean active() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM security_console_operator WHERE id=? AND active=1 AND delete_flag=0",Integer.class,settings.getOperatorId())==1;
    }
    private void audit(String operation,String target,String key,String result,String reason) {
        var context=ConsoleRequestContext.current();
        jdbc.update("INSERT INTO security_console_action(operator_id,operation_code,target_type,target_key,result,reason,correlation_id,source_key) VALUES(?,?,?,?,?,?,?,?)",
            members==null?settings.getOperatorId():members.identity().operatorId(),operation,target,key,result,reason,context.id(),context.source());
        if(evidence!=null)evidence.record(context);
    }
    public LoginResult login(String login,String password,String otp) {
        if(members!=null)return members.login(login,password,otp);
        return transaction.execute(status -> {
            Map<String,Object> row=operator(); LocalDateTime current=now();
            LocalDateTime until=storedTime(row.get("blocked_until"));
            if (!active(row)) { audit("LOGIN_REJECTED","OPERATOR",null,"REJECTED",null); return new LoginResult(false,900); }
            if (until!=null && until.isAfter(current)) return new LoginResult(false,Math.max(1,Duration.between(current,until).toSeconds()));
            var policy=settings.loginPolicy();if(ConsoleLoginState.read(row).protectedLogin())return new LoginResult(false,0,null,true);
            if(!settings.getLoginId().equals(login)){encoder.matches(password!=null && password.length()<=512?password:"",settings.getPasswordHash());audit("LOGIN_REJECTED","OPERATOR",null,"REJECTED",null);return new LoginResult(false,0);}
            long step=Totp.matchingStep(settings.getTotpSecret(),otp,clock.instant());
            boolean matches=encoder.matches(password!=null && password.length()<=512?password:"",settings.getPasswordHash());
            boolean valid=settings.getLoginId().equals(login) && matches && step>((Number)row.get("last_totp_step")).longValue();
            if (valid) {
                ConsoleLoginState.success(jdbc,settings.getOperatorId(),step,current);
                audit("LOGIN_SUCCESS","OPERATOR",null,"SUCCESS",null); return new LoginResult(true,0,new ConsoleIdentity(settings.getOperatorId(),settings.getLoginId(),"OWNER",ConsoleMembers.number(row,"auth_version")));
            }
            var next=ConsoleLoginState.failure(jdbc,settings,row,current);
            audit(next.protectedLogin()?"LOGIN_PROTECTED":"LOGIN_REJECTED","OPERATOR",null,"REJECTED",null);
            return new LoginResult(false,policy.retryAfter(next,current),null,next.protectedLogin());
        });
    }
    private void stepUp(String otp) {
        if(members!=null){members.stepUp(otp,"OPERATE");return;}
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth==null || !(auth.getPrincipal() instanceof ConsoleIdentity identity) || !identity.operatorId().equals(settings.getOperatorId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        int result=transaction.execute(status->{
            var row=operator(); if(!active(row))return 403;
            var policy=settings.loginPolicy();if(ConsoleLoginState.read(row).protectedLogin())return 403;
            if(policy.retryAfter(ConsoleLoginState.read(row),now())>0)return 429;
            long step=Totp.matchingStep(settings.getTotpSecret(),otp,clock.instant());
            if(step<0||step<=((Number)row.get("last_totp_step")).longValue()) {
                var next=ConsoleLoginState.failure(jdbc,settings,row,now());
                jdbc.update("UPDATE security_console_operator SET otp_failures=otp_failures+1 WHERE id=?",settings.getOperatorId());
                audit(next.protectedLogin()?"LOGIN_PROTECTED":"STEP_UP_REJECTED","OPERATOR",null,"REJECTED",null);
                return next.protectedLogin()||policy.retryAfter(next,now())>0?429:400;
            }
            jdbc.update("UPDATE security_console_operator SET last_totp_step=?,otp_failures=0,otp_blocked_until=NULL WHERE id=?",step,settings.getOperatorId());
            audit("STEP_UP_SUCCESS","OPERATOR",null,"SUCCESS",null);return 200;
        });
        if(result!=200)throw new ResponseStatusException(HttpStatus.valueOf(result),result==429?"認証試行が制限又は保護されています。":"新しい認証コードを確認してください。直前と同じコードは使用できません。");
    }
    private String reason(String value) {
        if(value==null || value.isBlank() || value.trim().codePointCount(0,value.trim().length())>300 || value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n')) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"操作理由を1～300文字で入力してください。");
        return value.trim();
    }
    public Map<String,Object> overview() {
        var result=new LinkedHashMap<String,Object>();
        result.put("limited",jdbc.queryForObject("SELECT COUNT(*) FROM security_login_attempt WHERE frozen=1 OR blocked_until>CURRENT_TIMESTAMP(6)",Long.class));
        result.put("protectedAccounts",jdbc.queryForObject("SELECT COUNT(*) FROM security_login_attempt WHERE scope='ACCOUNT' AND frozen=1",Long.class));
        result.put("sources",jdbc.queryForObject("SELECT COUNT(*) FROM security_login_attempt WHERE scope='SOURCE' AND blocked_until>CURRENT_TIMESTAMP(6)",Long.class));
        result.put("rejected",jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_log WHERE result='REJECTED' AND occurred_at>=CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY",Long.class));
        result.put("time",now()); return result;
    }
    public List<Map<String,Object>> accounts(String keyword) {
        if(keyword.length()>50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"検索は50文字以内です。");
        String query="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return jdbc.queryForList("SELECT u.id,u.username,u.role,u.status,COALESCE(a.attempts,0) AS attempts,COALESCE(a.frozen,0) AS frozen,a.blocked_until FROM sys_user u LEFT JOIN security_login_attempt a ON a.scope='ACCOUNT' AND a.key_hash=SHA2(CONCAT('user:',u.id),256) WHERE u.username LIKE ? ESCAPE '!' ORDER BY u.id LIMIT 100",query);
    }
    public List<Map<String,Object>> sources(int page) {
        var rows=jdbc.queryForList("SELECT s.source_key AS key_hash,COALESCE(a.attempts,0) AS attempts,a.blocked_until,COALESCE(p.last_seen_at,a.updated_at) AS updated_at,e.correlation_id,e.occurred_at,e.producer,e.key_id,e.encrypted_detail FROM (SELECT key_hash AS source_key FROM security_login_attempt WHERE scope='SOURCE' UNION SELECT source_key FROM security_source_profile) s LEFT JOIN security_login_attempt a ON a.scope='SOURCE' AND a.key_hash=s.source_key LEFT JOIN security_source_profile p ON p.source_key=s.source_key LEFT JOIN security_access_evidence e ON e.correlation_id=p.evidence_id ORDER BY updated_at DESC,s.source_key LIMIT 20 OFFSET ?",page*20);
        if(evidence!=null)evidence.enrich(rows);return rows;
    }
    public List<Map<String,Object>> events(String kind,int page) {
        if(!Set.of("ALL","LOGIN","REJECTED").contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"表示条件が不正です。");
        String predicate=switch(kind){case "LOGIN"->"operation_code LIKE 'LOGIN_%'";case "REJECTED"->"result='REJECTED'";default->"1=1";};
        var rows=jdbc.queryForList("SELECT a.id,a.occurred_at,a.operation_code,a.target_type,a.target_id,a.result,a.correlation_id,a.source_key,e.producer,e.key_id,e.encrypted_detail FROM (SELECT * FROM security_audit_log WHERE "+predicate+" ORDER BY occurred_at DESC,id DESC LIMIT 20 OFFSET ?) a LEFT JOIN security_access_evidence e ON e.correlation_id=a.correlation_id ORDER BY a.occurred_at DESC,a.id DESC",page*20);
        if(evidence!=null)evidence.enrich(rows);return rows;
    }
    public List<Map<String,Object>> actions(int page) {
        var rows=jdbc.queryForList("SELECT a.id,a.created_at,a.operation_code,a.target_type,a.target_key,a.result,a.reason,a.correlation_id,o.login_id,e.occurred_at,e.producer,e.key_id,e.encrypted_detail FROM (SELECT * FROM security_console_action ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET ?) a LEFT JOIN security_console_operator o ON o.id=a.operator_id LEFT JOIN security_access_evidence e ON e.correlation_id=a.correlation_id ORDER BY a.created_at DESC,a.id DESC",page*20);
        if(evidence!=null)evidence.enrich(rows);return rows;
    }
    public void account(long id,boolean freeze,String enteredReason,String otp) {
        if(id<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"対象IDを確認してください。");
        if(members!=null){members.require(freeze?"OPERATE":"OWNER",false);if(freeze && !members.identity().role().equals("OWNER") && !"EMPLOYEE".equals(accountRole(id,false)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"管理権限を持つ社員の保護は最高管理者へ申請してください。");}
        String why=reason(enteredReason); stepUp(otp);
        transaction.executeWithoutResult(status->{
        if(!active(operator()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(members!=null && (!freeze || !members.identity().role().equals("OWNER") && !"EMPLOYEE".equals(accountRole(id,true))))members.require("OWNER",true);
        accountChange(id,freeze,why);
        });
    }
    private String accountRole(long id,boolean lock){
        var roles=jdbc.queryForList("SELECT role FROM sys_user WHERE id=?"+(lock?" FOR SHARE":""),String.class,id);
        if(roles.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"対象が見つかりません。");
        return roles.getFirst();
    }
    private void accountChange(long id,boolean freeze,String why){
        if(id<=0 || jdbc.queryForObject("SELECT COUNT(id) FROM sys_user WHERE id=?",Integer.class,id)!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"対象が見つかりません。");
        String key=OperatorCrypto.hash("user:"+id);
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,window_start) VALUES('ACCOUNT',?,?) ON DUPLICATE KEY UPDATE key_hash=key_hash",key,Timestamp.valueOf(now()));
        var before=jdbc.queryForMap("SELECT attempts,frozen,blocked_until FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=? FOR UPDATE",key);
        if(freeze && ((Number)before.get("frozen")).intValue()==1) throw new ResponseStatusException(HttpStatus.CONFLICT,"すでに保護中です。");
        if(freeze) jdbc.update("UPDATE security_login_attempt SET frozen=1,protected_at=?,protection_reason='MANUAL_PROTECTION',protection_version=protection_version+1 WHERE scope='ACCOUNT' AND key_hash=?",Timestamp.valueOf(now()),key);
        else jdbc.update("UPDATE security_login_attempt SET frozen=0,attempts=0,failure_stage=0,protected_at=NULL,protection_reason=NULL,protection_version=protection_version+1,blocked_until=NULL,window_start=? WHERE scope='ACCOUNT' AND key_hash=?",Timestamp.valueOf(now()),key);
        audit(freeze?"ACCOUNT_PROTECT":"ACCOUNT_RELEASE","ACCOUNT",key,"SUCCESS",why);
        // 社員の有効状態・役割・パスワードを変更しない。管理者を自動再生成しない。
    }
    public void source(String key,Integer minutes,String enteredReason,String otp) {
        if(key==null || !key.matches("[a-f0-9]{64}") || minutes!=null && !Set.of(15,60,1440).contains(minutes)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"制限条件が不正です。");
        if(members!=null)members.require(minutes==null || minutes==1440?"OWNER":"OPERATE",false);
        String why=reason(enteredReason); stepUp(otp);
        transaction.executeWithoutResult(status->{
        if(!active(operator()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(members!=null && (minutes==null || minutes==1440))members.require("OWNER",true);
        sourceChange(key,minutes,why);
        });
    }
    private void sourceChange(String key,Integer minutes,String why){
        jdbc.update("INSERT INTO security_login_attempt(scope,key_hash,window_start) SELECT 'SOURCE',?,? WHERE EXISTS(SELECT 1 FROM security_source_profile WHERE source_key=?) ON DUPLICATE KEY UPDATE key_hash=key_hash",key,Timestamp.valueOf(now()),key);
        var found=jdbc.queryForList("SELECT key_hash,blocked_until FROM security_login_attempt WHERE scope='SOURCE' AND key_hash=? FOR UPDATE",key);
        if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"対象が見つかりません。");
        if(members!=null && !members.identity().role().equals("OWNER")){var until=storedTime(found.getFirst().get("blocked_until"));if(minutes==null || until!=null && until.isAfter(now().plusMinutes(minutes)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"既存制限の短縮・解除は最高管理者へ申請してください。");}
        jdbc.update("UPDATE security_login_attempt SET attempts=0,window_start=?,blocked_until=? WHERE scope='SOURCE' AND key_hash=?",Timestamp.valueOf(now()),minutes==null?null:Timestamp.valueOf(now().plusMinutes(minutes)),key);
        audit(minutes==null?"SOURCE_RELEASE":"SOURCE_RESTRICT","SOURCE",key,"SUCCESS",why);
    }
    @Transactional public void logout() { audit("LOGOUT","OPERATOR",null,"SUCCESS",null); }
    public void logout(ConsoleIdentity identity){if(members==null){logout();return;}transaction.executeWithoutResult(status->members.audit(identity.operatorId(),"LOGOUT",identity.operatorId(),"SUCCESS",null));}
    public List<Map<String,Object>> reviews(){var actor=members.require("READ",false);return jdbc.queryForList("SELECT r.id,r.operation_code,r.target_value,r.minutes,r.reason,r.state,r.review_reason,r.created_at,r.updated_at,r.session_sequence,o.login_id,s.login_id AS target_login,s.realm AS target_realm FROM security_console_review r JOIN security_console_operator o ON o.id=r.requester_id LEFT JOIN security_session_registry s ON r.operation_code IN('SESSION_REVOKE','SESSION_REVOKE_ALL') AND s.ref_id=r.target_value AND (s.realm='OA' AND s.owner_key='00000000-0000-0000-0000-000000000000' OR s.realm='CONTROL' AND s.owner_key=r.owner_id) WHERE r.owner_id=? AND (?='OWNER' OR r.requester_id=?) ORDER BY r.created_at DESC,r.id DESC LIMIT 100",settings.getOperatorId(),actor.role(),actor.operatorId());}
    public void requestReview(String operation,String target,Integer minutes,String reason,String otp){
        members.require("REVIEW_REQUEST",false);if(!Set.of("ACCOUNT_PROTECT","ACCOUNT_RELEASE","SOURCE_RESTRICT","SOURCE_RELEASE").contains(operation))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(operation.startsWith("ACCOUNT") && (target==null || !target.matches("[1-9][0-9]{0,18}")) || operation.startsWith("SOURCE") && (target==null || !target.matches("[a-f0-9]{64}")) || operation.equals("SOURCE_RESTRICT") && (minutes==null || !Set.of(15,60,1440).contains(minutes)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"申請対象を確認してください。");
        if(operation.startsWith("ACCOUNT")){try{Long.parseLong(target);}catch(NumberFormatException outOfRange){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"対象IDは正のBIGINT範囲で入力してください。");}}
        String why=ConsoleMembers.reason(reason);members.stepUp(otp,"REVIEW_REQUEST");transaction.executeWithoutResult(status->{var actor=members.require("REVIEW_REQUEST",true);jdbc.update("INSERT INTO security_console_review(owner_id,requester_id,operation_code,target_value,minutes,reason) VALUES(?,?,?,?,?,?)",settings.getOperatorId(),actor.operatorId(),operation,target,operation.equals("SOURCE_RESTRICT")?minutes:null,why);members.audit(actor.operatorId(),"REVIEW_REQUEST",target,"SUCCESS",why);});
    }
    public void review(long id,boolean approve,String reason,String otp){String why=ConsoleMembers.reason(reason);members.stepUp(otp,"OWNER");transaction.executeWithoutResult(status->{var actor=members.require("OWNER",true);var found=jdbc.queryForList("SELECT * FROM security_console_review WHERE id=? AND owner_id=? FOR UPDATE",id,settings.getOperatorId());if(found.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND);var request=found.getFirst();if(!"PENDING".equals(request.get("state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"この申請は審査済みです。");
        if(approve){var requester=members.row((String)request.get("requester_id"),true);if(!members.enabled(requester) || !Set.of("OWNER","OPERATOR").contains(requester.get("role")))throw new ResponseStatusException(HttpStatus.CONFLICT,"申請者の運用権限は無効です。申請を却下してください。");String operation=(String)request.get("operation_code"),target=(String)request.get("target_value");if(operation.startsWith("SESSION_"))sessionOperations.executeReviewed(request,why);else if(operation.startsWith("ACCOUNT"))accountChange(Long.parseLong(target),operation.equals("ACCOUNT_PROTECT"),why);else sourceChange(target,operation.equals("SOURCE_RESTRICT")?((Number)request.get("minutes")).intValue():null,why);}
        jdbc.update("UPDATE security_console_review SET state=?,reviewer_id=?,review_reason=? WHERE id=?",approve?"APPROVED":"REJECTED",actor.operatorId(),why,id);members.audit(actor.operatorId(),approve?"REVIEW_APPROVE":"REVIEW_REJECT",String.valueOf(id),"SUCCESS",why);
    });}
}
