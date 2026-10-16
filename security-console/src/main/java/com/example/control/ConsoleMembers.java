package com.example.control;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 個人別の認証、開通、権限失効。最高管理者の秘密情報は引き続き本機設定のみ。 */
@Service
public class ConsoleMembers {
    private final JdbcTemplate jdbc;
    private final OperatorSettings owner;
    private final Clock clock;
    private final MemberSecrets secrets;
    private final ConsoleEvidence evidence;
    private final TransactionTemplate tx;
    private com.example.security.SessionRegistry registry;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder=OperatorCrypto.encoder();
    public record Registration(String id,String token,long version) {}
    public record Setup(String id,long version) {}
    public ConsoleMembers(JdbcTemplate jdbc,OperatorSettings owner,Clock clock,MemberSecrets secrets,ConsoleEvidence evidence,PlatformTransactionManager manager){this.jdbc=jdbc;this.owner=owner;this.clock=clock;this.secrets=secrets;this.evidence=evidence;tx=new TransactionTemplate(manager);}
    @org.springframework.beans.factory.annotation.Autowired public void setRegistry(com.example.security.SessionRegistry value){registry=value;}
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Tokyo")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    static LocalDateTime time(Object value){return value==null?null:value instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    Map<String,Object> row(String id,boolean lock){
        var rows=jdbc.queryForList("SELECT * FROM security_console_operator WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),id,owner.getOperatorId());
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"この管理権限は利用できません。");return rows.getFirst();
    }
    boolean enabled(Map<String,Object> row){return number(row,"active")==1 && number(row,"delete_flag")==0 && "ACTIVE".equals(row.get("enrollment_state"));}
    boolean matches(Map<String,Object> row,ConsoleIdentity identity){return enabled(row) && number(row,"login_protected")==0 && identity.version()==number(row,"auth_version") && identity.role().equals(row.get("role"));}
    public boolean valid(ConsoleIdentity identity){
        var rows=jdbc.queryForList("SELECT active,delete_flag,enrollment_state,auth_version,role,login_protected FROM security_console_operator WHERE id=? AND owner_id=?",identity.operatorId(),owner.getOperatorId());
        return rows.size()==1 && matches(rows.getFirst(),identity);
    }
    public ConsoleIdentity identity(){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null || !(auth.getPrincipal() instanceof ConsoleIdentity id))throw new ResponseStatusException(HttpStatus.FORBIDDEN);return id;}
    public ConsoleIdentity require(String permission,boolean lock){
        var id=identity();var value=row(id.operatorId(),lock);boolean allowed=matches(value,id) && (id.role().equals("OWNER") || permission.equals("READ") || (id.role().equals("OPERATOR") && Set.of("OPERATE","REVIEW_REQUEST","PERSONAL").contains(permission)) || (id.role().equals("OBSERVER") && permission.equals("PERSONAL")));
        if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"この操作は許可されていません。");
        if(lock && registry!=null)registry.requireCurrent();return id;
    }
    public void audit(String actor,String operation,String target,String result,String reason){
        var context=ConsoleRequestContext.current();
        jdbc.update("INSERT INTO security_console_action(operator_id,operation_code,target_type,target_key,result,reason,correlation_id,source_key) VALUES(?,?,?,?,?,?,?,?)",actor,operation,"OPERATOR",target==null?null:OperatorCrypto.hash(target),result,reason,context.id(),context.source());
        evidence.record(context);
    }
    public void denied(){tx.executeWithoutResult(status->{var auth=SecurityContextHolder.getContext().getAuthentication();String actor=auth!=null && auth.getPrincipal() instanceof ConsoleIdentity id && jdbc.queryForObject("SELECT COUNT(*) FROM security_console_operator WHERE id=? AND owner_id=?",Integer.class,id.operatorId(),owner.getOperatorId())==1?id.operatorId():null;audit(actor,"PERMISSION_REJECTED",null,"REJECTED","権限又はCSRF検証の拒否");});}
    public void registerOwner(){tx.executeWithoutResult(status->{
        jdbc.update("INSERT INTO security_console_operator(id,login_id,owner_id,role) VALUES(?,?,?,'OWNER') ON DUPLICATE KEY UPDATE owner_id=IF(owner_id IS NULL,VALUES(owner_id),owner_id)",owner.getOperatorId(),owner.getLoginId(),owner.getOperatorId());
        var value=row(owner.getOperatorId(),true);if(!owner.getLoginId().equals(value.get("login_id")) || !"OWNER".equals(value.get("role")))throw new IllegalStateException("最高管理者の登録が一致しません。");
    });}
    boolean entryLimited(){
        String source=ConsoleRequestContext.current().source();
        jdbc.update("INSERT INTO security_console_entry_limit(owner_id,source_key,window_start) VALUES(?,?,?) ON DUPLICATE KEY UPDATE owner_id=owner_id",owner.getOperatorId(),source,Timestamp.valueOf(now()));
        var limit=jdbc.queryForMap("SELECT * FROM security_console_entry_limit WHERE owner_id=? AND source_key=? FOR UPDATE",owner.getOperatorId(),source);
        var until=time(limit.get("blocked_until"));return until!=null && until.isAfter(now());
    }
    void entryFailure(){
        String source=ConsoleRequestContext.current().source();
        jdbc.update("UPDATE security_console_entry_limit SET attempts=IF(window_start<=?,1,attempts+1),blocked_until=IF(window_start>? AND attempts>=10,?,NULL),window_start=IF(window_start<=?, ?,window_start) WHERE owner_id=? AND source_key=?",Timestamp.valueOf(now().minusMinutes(1)),Timestamp.valueOf(now().minusMinutes(1)),Timestamp.valueOf(now().plusMinutes(1)),Timestamp.valueOf(now().minusMinutes(1)),Timestamp.valueOf(now()),owner.getOperatorId(),source);
    }
    public ConsoleStore.LoginResult login(String login,String password,String otp){return tx.execute(status->{
        if(entryLimited())return new ConsoleStore.LoginResult(false,60);
        String name=login!=null && login.matches("[A-Za-z0-9_-]{4,50}")?login:"";
        var rows=jdbc.queryForList("SELECT * FROM security_console_operator WHERE owner_id=? AND login_id=? FOR UPDATE",owner.getOperatorId(),name);
        if(rows.size()!=1){encoder.matches(password!=null && password.length()<=512?password:"",owner.getPasswordHash());entryFailure();audit(null,"LOGIN_REJECTED",null,"REJECTED","認証情報不一致");return new ConsoleStore.LoginResult(false,0);}
        var value=rows.getFirst();String id=(String)value.get("id");
        if(!enabled(value)){encoder.matches(password!=null && password.length()<=512?password:"",owner.getPasswordHash());entryFailure();audit(id,"LOGIN_REJECTED",id,"REJECTED","認証情報不一致");return new ConsoleStore.LoginResult(false,0);}
        var policy=owner.loginPolicy();var state=ConsoleLoginState.read(value);
        if(state.protectedLogin())return new ConsoleStore.LoginResult(false,0,null,true);
        long wait=policy.retryAfter(state,now());if(wait>0)return new ConsoleStore.LoginResult(false,wait);
        boolean primary=id.equals(owner.getOperatorId());String hash=primary?owner.getPasswordHash():(String)value.get("password_hash");
        boolean passwordOk=hash!=null && encoder.matches(password!=null && password.length()<=512?password:"",hash);
        long step=passwordOk?Totp.matchingStep(primary?owner.getTotpSecret():secrets.decrypt(owner.getOperatorId(),id,(String)value.get("totp_secret_enc")),otp,clock.instant()):-1;
        if(passwordOk && step>number(value,"last_totp_step")){
            ConsoleLoginState.success(jdbc,id,step,now());audit(id,"LOGIN_SUCCESS",id,"SUCCESS",null);
            return new ConsoleStore.LoginResult(true,0,new ConsoleIdentity(id,name,(String)value.get("role"),number(value,"auth_version")));
        }
        var next=ConsoleLoginState.failure(jdbc,owner,value,now());
        audit(id,next.protectedLogin()?"LOGIN_PROTECTED":"LOGIN_REJECTED",id,"REJECTED","認証情報不一致");
        return new ConsoleStore.LoginResult(false,policy.retryAfter(next,now()),null,next.protectedLogin());
    });}
    public void stepUp(String otp,String permission){
        int result=tx.execute(status->{
            var actor=require(permission,true);var value=row(actor.operatorId(),true);var policy=owner.loginPolicy();
            if(policy.retryAfter(ConsoleLoginState.read(value),now())>0)return 429;
            String secret=actor.operatorId().equals(owner.getOperatorId())?owner.getTotpSecret():secrets.decrypt(owner.getOperatorId(),actor.operatorId(),(String)value.get("totp_secret_enc"));
            long step=Totp.matchingStep(secret,otp,clock.instant());
            if(step<0 || step<=number(value,"last_totp_step")){
                var next=ConsoleLoginState.failure(jdbc,owner,value,now());
                jdbc.update("UPDATE security_console_operator SET otp_failures=otp_failures+1 WHERE id=?",actor.operatorId());
                audit(actor.operatorId(),next.protectedLogin()?"LOGIN_PROTECTED":"STEP_UP_REJECTED",actor.operatorId(),"REJECTED",null);
                return next.protectedLogin()||policy.retryAfter(next,now())>0?429:400;
            }
            jdbc.update("UPDATE security_console_operator SET last_totp_step=?,otp_failures=0,otp_blocked_until=NULL WHERE id=?",step,actor.operatorId());audit(actor.operatorId(),"STEP_UP_SUCCESS",actor.operatorId(),"SUCCESS",null);return 200;
        });
        if(result!=200)throw new ResponseStatusException(HttpStatus.valueOf(result),result==429?"認証試行が制限又は保護されています。時間をおくか、最高管理者へ確認してください。":"新しい6桁の認証コードを確認してください。使用済みコードは再利用できません。");
    }
    public static String reason(String value){if(value==null || value.isBlank() || value.trim().length()>300 || value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"操作理由を1～300文字で入力してください。");return value.trim();}
    static String name(String value){if(value==null || value.isBlank() || value.trim().length()>50 || value.codePoints().anyMatch(Character::isISOControl))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"氏名を1～50文字で入力してください。");return value.trim();}
    static String role(String role){if(!Set.of("OBSERVER","OPERATOR").contains(Objects.toString(role,"")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"監視のみ又は監視・運用を選択してください。");return role;}
    public List<Map<String,Object>> list(){require("OWNER",false);return jdbc.queryForList("SELECT id,login_id,display_name,role,active,delete_flag,enrollment_state,enrollment_until,failed_attempts,failure_stage,blocked_until,login_protected,protected_at,created_at,updated_at FROM security_console_operator WHERE owner_id=? AND id<>? AND delete_flag=0 ORDER BY created_at DESC LIMIT 200",owner.getOperatorId(),owner.getOperatorId());}
    public Registration create(String login,String display,String role,String why,String otp){
        if(login==null || !login.matches("[A-Za-z0-9_-]{4,50}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"管理IDは半角英数字・_・-の4～50文字です。");String title=name(display),chosen=role(role),reason=reason(why);stepUp(otp,"OWNER");
        return tx.execute(status->{var actor=require("OWNER",true);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM security_console_operator WHERE owner_id=? AND login_id=?",Integer.class,owner.getOperatorId(),login)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"この管理IDは使用済みです。");
            String id=UUID.randomUUID().toString(),token=secrets.token();jdbc.update("INSERT INTO security_console_operator(id,login_id,owner_id,role,display_name,active,enrollment_state,invitation_hash,enrollment_until) VALUES(?,?,?,?,?,0,'INVITED',?,?)",id,login,owner.getOperatorId(),chosen,title,OperatorCrypto.hash(token),Timestamp.valueOf(now().plusHours(24)));audit(actor.operatorId(),"MEMBER_CREATE",id,"SUCCESS",reason);return new Registration(id,token,0);
        });
    }
    public Registration change(String id,String action,String display,String role,String why,String otp){
        if(!Set.of("edit","enable","disable","delete","reinvite","release").contains(action))throw new ResponseStatusException(HttpStatus.NOT_FOUND);String reason=reason(why);String chosen=action.equals("edit")?role(role):null,title=action.equals("edit")?name(display):null;stepUp(otp,"OWNER");
        return tx.execute(status->{var actor=require("OWNER",true);if(id.equals(owner.getOperatorId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"最高管理者はこの画面から変更できません。");var member=row(id,true);
            if(number(member,"delete_flag")!=0)throw new ResponseStatusException(HttpStatus.CONFLICT,"削除済みの担当者は再利用できません。");
            if(action.equals("release")){
                if(number(member,"login_protected")==0 && time(member.get("blocked_until"))==null && number(member,"failed_attempts")==0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"解除する認証制限はありません。");
                jdbc.update("UPDATE security_console_operator SET failed_attempts=0,failure_window_start=?,failure_stage=0,blocked_until=NULL,login_protected=0,protected_at=NULL,protection_reason=NULL,otp_failures=0,otp_blocked_until=NULL,auth_version=auth_version+1 WHERE id=?",Timestamp.valueOf(now()),id);
            }else if(action.equals("enable")){
                if(!Set.of("PENDING","DISABLED").contains(member.get("enrollment_state")) || member.get("totp_secret_enc")==null || member.get("password_hash")==null || number(member,"last_totp_step")<0)throw new ResponseStatusException(HttpStatus.CONFLICT,"本人の認証器登録が完了していません。");
                jdbc.update("UPDATE security_console_operator SET active=1,enrollment_state='ACTIVE',enrollment_until=NULL,auth_version=auth_version+1 WHERE id=?",id);
            }else if(action.equals("edit"))jdbc.update("UPDATE security_console_operator SET display_name=?,role=?,auth_version=auth_version+1 WHERE id=?",title,chosen,id);
            else if(action.equals("delete"))jdbc.update("UPDATE security_console_operator SET active=0,enrollment_state='DELETED',delete_flag=1,deleted_at=?,auth_version=auth_version+1,invitation_hash=NULL,totp_secret_enc=NULL,password_hash=NULL WHERE id=?",Timestamp.valueOf(now()),id);
            else if(action.equals("disable"))jdbc.update("UPDATE security_console_operator SET active=0,enrollment_state='DISABLED',invitation_hash=NULL,auth_version=auth_version+1 WHERE id=?",id);
            else{
                String token=secrets.token();jdbc.update("UPDATE security_console_operator SET active=0,enrollment_state='INVITED',invitation_hash=?,enrollment_until=?,auth_version=auth_version+1,password_hash=NULL,totp_secret_enc=NULL,last_totp_step=-1,failed_attempts=0,failure_window_start=NULL,failure_stage=0,blocked_until=NULL,login_protected=0,protected_at=NULL,protection_reason=NULL,otp_failures=0,otp_blocked_until=NULL WHERE id=?",OperatorCrypto.hash(token),Timestamp.valueOf(now().plusHours(24)),id);audit(actor.operatorId(),"MEMBER_REINVITE",id,"SUCCESS",reason);return new Registration(id,token,number(member,"auth_version")+1);
            }
            audit(actor.operatorId(),"MEMBER_"+action.toUpperCase(Locale.ROOT),id,"SUCCESS",reason);return new Registration(id,"",number(member,"auth_version")+1);
        });
    }
    public Setup start(String login,String token,String password){MemberSecrets.password(password);return tx.execute(status->{
        if(entryLimited())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"時間をおいて再度お試しください。");
        var found=jdbc.queryForList("SELECT * FROM security_console_operator WHERE owner_id=? AND login_id=? FOR UPDATE",owner.getOperatorId(),Objects.toString(login,""));
        Map<String,Object> member=found.size()==1?found.getFirst():null;String actual=token!=null && token.matches("[A-Za-z0-9_-]{43}")?OperatorCrypto.hash(token):"";
        if(member==null || !"INVITED".equals(member.get("enrollment_state")) || number(member,"delete_flag")!=0 || time(member.get("enrollment_until"))==null || !time(member.get("enrollment_until")).isAfter(now()) || !MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),Objects.toString(member.get("invitation_hash"),"").getBytes(StandardCharsets.US_ASCII))){entryFailure();audit(null,"ENROLL_REJECTED",null,"REJECTED","開通情報不一致");return null;}
        String id=(String)member.get("id");String encrypted=secrets.encrypt(owner.getOperatorId(),id,secrets.totp());long version=number(member,"auth_version")+1;
        jdbc.update("UPDATE security_console_operator SET password_hash=?,totp_secret_enc=?,invitation_hash=NULL,enrollment_state='SETUP',enrollment_until=?,auth_version=? WHERE id=?",encoder.encode(password),encrypted,Timestamp.valueOf(now().plusMinutes(10)),version,id);audit(id,"ENROLL_START",id,"SUCCESS",null);return new Setup(id,version);
    });}
    Map<String,Object> setupRow(Setup setup,boolean lock){if(setup==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"開通情報を入力してください。");var member=row(setup.id(),lock);if(number(member,"auth_version")!=setup.version() || number(member,"delete_flag")!=0 || !Set.of("SETUP","PENDING").contains(member.get("enrollment_state")) || time(member.get("enrollment_until"))==null || !time(member.get("enrollment_until")).isAfter(now()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登録期限が切れました。最高管理者へ招待の再発行を依頼してください。");return member;}
    public Map<String,Object> setup(Setup setup){var member=setupRow(setup,false);var result=new LinkedHashMap<String,Object>();result.put("state",member.get("enrollment_state"));result.put("loginId",member.get("login_id"));result.put("until",member.get("enrollment_until"));result.put("expiresInMilliseconds",Math.max(0,Duration.between(now(),time(member.get("enrollment_until"))).toMillis()));if("SETUP".equals(member.get("enrollment_state")))result.put("qr",EnrollmentQr.png((String)member.get("login_id"),secrets.decrypt(owner.getOperatorId(),setup.id(),(String)member.get("totp_secret_enc"))));return result;}
    public boolean confirm(Setup setup,String otp){return tx.execute(status->{var member=setupRow(setup,true);if(!"SETUP".equals(member.get("enrollment_state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"認証器は登録済みです。");var until=time(member.get("otp_blocked_until"));if(until!=null && until.isAfter(now()))return false;
        long step=Totp.matchingStep(secrets.decrypt(owner.getOperatorId(),setup.id(),(String)member.get("totp_secret_enc")),otp,clock.instant());
        if(step<0){int count=(int)number(member,"otp_failures")+1;jdbc.update("UPDATE security_console_operator SET otp_failures=?,otp_blocked_until=? WHERE id=?",count,count>=5?Timestamp.valueOf(now().plusMinutes(15)):null,setup.id());audit(setup.id(),"ENROLL_REJECTED",setup.id(),"REJECTED","認証コード不一致");return false;}
        jdbc.update("UPDATE security_console_operator SET enrollment_state='PENDING',last_totp_step=?,otp_failures=0,otp_blocked_until=NULL WHERE id=?",step,setup.id());audit(setup.id(),"ENROLL_COMPLETE",setup.id(),"SUCCESS",null);return true;
    });}
    public void password(String current,String next,String otp){MemberSecrets.password(next);require("PERSONAL",false);if(identity().operatorId().equals(owner.getOperatorId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"最高管理者の資格情報は本機の保守手順で管理してください。");stepUp(otp,"PERSONAL");
        boolean changed=tx.execute(status->{var actor=require("PERSONAL",true);var member=row(actor.operatorId(),true);if(current==null || current.length()>512 || !encoder.matches(current,(String)member.get("password_hash"))){audit(actor.operatorId(),"PASSWORD_REJECTED",actor.operatorId(),"REJECTED",null);return false;}jdbc.update("UPDATE security_console_operator SET password_hash=?,auth_version=auth_version+1 WHERE id=?",encoder.encode(next),actor.operatorId());audit(actor.operatorId(),"PASSWORD_CHANGE",actor.operatorId(),"SUCCESS",null);return true;});
        if(!changed)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"現在のパスワードを確認してください。");
    }
}
