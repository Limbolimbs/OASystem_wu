package com.example.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import com.example.audit.AccessFields;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 認証Cookieとは別の会話参照。発行と全会話終了は本人別ロックで直列化する。
 * DATETIME(6)には日本時間のLocalDateTimeを直接渡す。
 * Timestampへ変換するとJVM既定時区と接続時区の差で保存時刻がずれるため使用しない。
 */
public final class SessionRegistry {
    public static final String REF="securitySessionReference";
    public static final String OA_OWNER="00000000-0000-0000-0000-000000000000";
    public record Binding(String ref,String subject,String role,long authVersion,long protectionVersion) {}
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String realm,ownerKey,nodeKey,runtime=UUID.randomUUID().toString();
    private final Duration idle,absolute;
    private final ThreadLocal<Binding> current=new ThreadLocal<>();
    public SessionRegistry(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,String realm,String ownerKey,String node,Duration idle,Duration absolute){
        if(!Set.of("OA","CONTROL").contains(realm)||!uuid(ownerKey)||idle.isZero()||idle.isNegative()||absolute.compareTo(idle)<0)throw new IllegalArgumentException("会話領域・期限を確認してください。");
        this.jdbc=jdbc;this.clock=clock;tx=new TransactionTemplate(manager);this.realm=realm;this.ownerKey=ownerKey;this.idle=idle;this.absolute=absolute;
        nodeKey=hash(node==null||node.isBlank()?realm+"|"+ownerKey+"|"+System.getenv("COMPUTERNAME")+"|"+System.getProperty("user.dir"):node);
    }
    public static boolean uuid(String value){return value!=null&&value.matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}");}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String ipKey(String ip){var address=AccessFields.literal(ip);return address==null?null:hash(address.getHostAddress());}
    public LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Tokyo")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    public static LocalDateTime time(Object value){return value==null?null:value instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
    public void initialize(){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN('security_session_registry','security_session_subject_guard')",Integer.class)!=2)throw new IllegalStateException("14の会話管理SQLの適用が必要です。");
        jdbc.update("UPDATE security_session_registry SET revoked_at=?,revocation_reason='RESTART',version=version+1 WHERE realm=? AND owner_key=? AND node_key=? AND runtime_id<>? AND revoked_at IS NULL",now(),realm,ownerKey,nodeKey,runtime);
    }
    public long lockSubject(String targetRealm,String targetOwner,String subject){
        jdbc.update("INSERT INTO security_session_subject_guard(realm,owner_key,subject_key) VALUES(?,?,?) ON DUPLICATE KEY UPDATE subject_key=subject_key",targetRealm,targetOwner,subject);
        return jdbc.queryForObject("SELECT sequence_no FROM security_session_subject_guard WHERE realm=? AND owner_key=? AND subject_key=? FOR UPDATE",Long.class,targetRealm,targetOwner,subject);
    }
    public String open(String subject,String login,String role,long authVersion,long protectionVersion,String evidence,String ip){
        if(subject==null||!subject.matches("[A-Za-z0-9_-]{1,64}")||login==null||login.length()>255)throw new IllegalArgumentException("会話の本人を確認してください。");
        return tx.execute(status->{
            long sequence=Math.addExact(lockSubject(realm,ownerKey,subject),1);
            if(!authoritative(subject,role,authVersion,protectionVersion,true))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"認証状態が変更されました。再ログインしてください。");
            LocalDateTime issued=now();String ref=UUID.randomUUID().toString();
            jdbc.update("UPDATE security_session_subject_guard SET sequence_no=? WHERE realm=? AND owner_key=? AND subject_key=?",sequence,realm,ownerKey,subject);
            jdbc.update("INSERT INTO security_session_registry(ref_id,realm,owner_key,subject_key,sequence_no,login_id,role,auth_version,protection_version,node_key,runtime_id,evidence_id,ip_key,issued_at,last_active_at,idle_expires_at,absolute_expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    ref,realm,ownerKey,subject,sequence,login,role,authVersion,protectionVersion,nodeKey,runtime,uuid(evidence)?evidence:null,ipKey(ip),issued,issued,issued.plus(idle),issued.plus(absolute));
            return ref;
        });
    }
    private boolean authoritative(String subject,String role,long authVersion,long protectionVersion,boolean lock){
        if(realm.equals("CONTROL"))return jdbc.queryForList("SELECT id FROM security_console_operator WHERE id=? AND owner_id=? AND active=1 AND delete_flag=0 AND enrollment_state='ACTIVE' AND login_protected=0 AND role=? AND auth_version=?"+(lock?" FOR SHARE":""),subject,ownerKey,role,authVersion).size()==1;
        var people=jdbc.queryForList("SELECT role,status,delete_flag,authentication_version FROM sys_user WHERE id=?"+(lock?" FOR SHARE":""),subject);
        if(people.size()!=1)return false;var user=people.getFirst();
        if(((Number)user.get("status")).intValue()!=1||((Number)user.get("delete_flag")).intValue()!=0||!role.equals(user.get("role"))||((Number)user.get("authentication_version")).longValue()!=authVersion)return false;
        var guards=jdbc.queryForList("SELECT frozen,protection_version FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?"+(lock?" FOR SHARE":""),hash("user:"+subject));
        return guards.isEmpty()?protectionVersion==0:((Number)guards.getFirst().get("frozen")).intValue()==0&&((Number)guards.getFirst().get("protection_version")).longValue()==protectionVersion;
    }
    public boolean valid(Binding binding,boolean lock){
        if(binding==null||!uuid(binding.ref()))return false;
        var rows=jdbc.queryForList("SELECT * FROM security_session_registry WHERE ref_id=? AND realm=? AND owner_key=? AND subject_key=?"+(lock?" FOR SHARE":""),binding.ref(),realm,ownerKey,binding.subject());
        if(rows.size()!=1)return false;var row=rows.getFirst();LocalDateTime t=now();
        return row.get("revoked_at")==null&&runtime.equals(row.get("runtime_id"))&&nodeKey.equals(row.get("node_key"))
                &&Objects.equals(row.get("role"),binding.role())&&((Number)row.get("auth_version")).longValue()==binding.authVersion()&&((Number)row.get("protection_version")).longValue()==binding.protectionVersion()
                &&!time(row.get("issued_at")).isAfter(t)&&!time(row.get("last_active_at")).isAfter(t)&&time(row.get("idle_expires_at")).isAfter(t)&&time(row.get("absolute_expires_at")).isAfter(t);
    }
    public void bind(Binding value){current.set(value);}
    public Binding binding(){return current.get();}
    public void clear(){current.remove();}
    public void requireCurrent(){if(!valid(current.get(),true))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"会話は終了しています。再ログインしてください。");}
    public boolean touch(String ref){
        LocalDateTime t=now();return jdbc.update("UPDATE security_session_registry SET last_active_at=?,idle_expires_at=LEAST(?,absolute_expires_at) WHERE ref_id=? AND realm=? AND owner_key=? AND runtime_id=? AND revoked_at IS NULL AND idle_expires_at>? AND absolute_expires_at>?",t,t.plus(idle),ref,realm,ownerKey,runtime,t,t)==1;
    }
    public void close(String ref,String reason){
        if(!uuid(ref))return;
        if(!Set.of("LOGOUT","LOCAL_END","SESSION_REVOKED","SESSION_PROTECTED","SESSION_IDLE_EXPIRED","SESSION_ABSOLUTE_EXPIRED","AUTH_CHANGED").contains(reason))reason="LOCAL_END";
        jdbc.update("UPDATE security_session_registry SET revoked_at=?,revocation_reason=?,version=version+1 WHERE ref_id=? AND realm=? AND owner_key=? AND runtime_id=? AND revoked_at IS NULL",now(),reason,ref,realm,ownerKey,runtime);
    }
}
