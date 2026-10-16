package com.example.control;

import com.example.security.SessionRegistry;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** 同一管理範囲の会話を参照・終了。Cookieではなく独立参照UUIDを使用する。 */
@Service
public class ConsoleSessions {
    private final JdbcTemplate jdbc;
    private final OperatorSettings settings;
    private final ConsoleMembers members;
    private final ConsoleEvidence evidence;
    private final SessionRegistry registry;
    private final TransactionTemplate tx;
    public ConsoleSessions(JdbcTemplate jdbc,OperatorSettings settings,ConsoleMembers members,ConsoleEvidence evidence,SessionRegistry registry,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.settings=settings;this.members=members;this.evidence=evidence;this.registry=registry;tx=new TransactionTemplate(manager);
    }
    private String base(){return "SELECT r.*,COALESCE(u.role,o.role,r.role) AS current_role,CASE "
            +"WHEN r.revoked_at IS NOT NULL THEN 'ENDED' "
            +"WHEN r.realm='OA' AND (u.id IS NULL OR u.status<>1 OR u.delete_flag<>0 OR u.authentication_version<>r.auth_version OR u.role<>r.role OR COALESCE(p.frozen,0)<>0 OR COALESCE(p.protection_version,0)<>r.protection_version) THEN 'ENDED' "
            +"WHEN r.realm='CONTROL' AND (o.id IS NULL OR o.owner_id<>r.owner_key OR o.active<>1 OR o.delete_flag<>0 OR o.enrollment_state<>'ACTIVE' OR o.auth_version<>r.auth_version OR o.role<>r.role OR o.login_protected<>0) THEN 'ENDED' "
            +"WHEN r.issued_at>? OR r.last_active_at>? OR r.idle_expires_at<=? OR r.absolute_expires_at<=? THEN 'EXPIRED' ELSE 'ACTIVE' END AS state "
            +"FROM security_session_registry r LEFT JOIN sys_user u ON r.realm='OA' AND CAST(u.id AS CHAR)=r.subject_key "
            +"LEFT JOIN security_login_attempt p ON r.realm='OA' AND p.scope='ACCOUNT' AND p.key_hash=SHA2(CONCAT('user:',r.subject_key),256) "
            +"LEFT JOIN security_console_operator o ON r.realm='CONTROL' AND o.id=r.subject_key "
            +"WHERE (r.realm='OA' AND r.owner_key='"+SessionRegistry.OA_OWNER+"' OR r.realm='CONTROL' AND r.owner_key=?)";}
    private ArrayList<Object> baseArgs(){var now=Timestamp.valueOf(registry.now());return new ArrayList<>(List.of(now,now,now,now,settings.getOperatorId()));}
    static boolean direct(ConsoleIdentity actor,Map<String,Object> row){
        if(actor.role().equals("OWNER"))return true;
        if("CONTROL".equals(row.get("realm")))return actor.operatorId().equals(row.get("subject_key"));
        return actor.role().equals("OPERATOR")&&"EMPLOYEE".equals(row.get("current_role"));
    }
    public Map<String,Object> list(String realm,String state,String keyword,String ip,int page){
        return tx.execute(status->read(realm,state,keyword,ip,page));
    }
    private Map<String,Object> read(String realm,String state,String keyword,String ip,int page){
        var actor=members.require("READ",true);
        if(!Set.of("ALL","OA","CONTROL").contains(realm)||!Set.of("ALL","ACTIVE","ENDED","EXPIRED").contains(state)||keyword.length()>50||page<0||page>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"検索条件を確認してください。");
        String ipKey=null;if(!ip.isBlank()){ipKey=SessionRegistry.ipKey(ip);if(ipKey==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"IPv4又はIPv6のアドレスを入力してください。");}
        String filter=" WHERE 1=1";var args=baseArgs();
        if(!realm.equals("ALL")){filter+=" AND s.realm=?";args.add(realm);}
        if(!state.equals("ALL")){filter+=" AND s.state=?";args.add(state);}
        if(!keyword.isBlank()){filter+=" AND s.login_id LIKE ? ESCAPE '!'";args.add("%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(ipKey!=null){filter+=" AND s.ip_key=?";args.add(ipKey);}
        String query="("+base()+") s";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+query+filter,Long.class,args.toArray());
        args.add(page*20);
        var rows=jdbc.queryForList("SELECT s.ref_id,s.realm,s.subject_key,s.login_id,s.current_role,s.owner_key,s.issued_at,s.last_active_at,s.idle_expires_at,s.absolute_expires_at,s.revoked_at,s.revocation_reason,s.version,s.state,e.correlation_id,e.occurred_at,e.producer,e.key_id,e.encrypted_detail FROM "+query+" LEFT JOIN security_access_evidence e ON e.correlation_id=s.evidence_id"+filter+" ORDER BY s.issued_at DESC,s.ref_id LIMIT 20 OFFSET ?",args.toArray());
        String current=registry.binding()==null?null:registry.binding().ref();
        for(var row:rows){boolean active="ACTIVE".equals(row.get("state")),can=direct(actor,row);row.put("canRevoke",active&&can);row.put("canRequest",active&&!can&&actor.role().equals("OPERATOR"));row.put("current",Objects.equals(row.get("ref_id"),current));row.remove("subject_key");row.remove("owner_key");}
        evidence.enrich(rows);
        members.audit(actor.operatorId(),"SESSION_LIST",null,"SUCCESS","セッション一覧の参照");
        return Map.of("items",rows,"total",total,"page",page,"pageSize",20,"time",registry.now());
    }
    private Map<String,Object> target(String ref,boolean lock){
        if(!SessionRegistry.uuid(ref))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"会話参照を確認してください。");
        var args=baseArgs();args.add(ref);
        var rows=jdbc.queryForList(base()+" AND r.ref_id=?",args.toArray());
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"対象の会話が見つかりません。");
        var row=rows.getFirst();
        if(lock){
            registry.lockSubject((String)row.get("realm"),(String)row.get("owner_key"),(String)row.get("subject_key"));
            var locked=jdbc.queryForMap("SELECT * FROM security_session_registry WHERE ref_id=? FOR UPDATE",ref);
            row.putAll(locked);
            if("OA".equals(row.get("realm"))){var person=jdbc.queryForList("SELECT role FROM sys_user WHERE id=? FOR SHARE",row.get("subject_key"));row.put("current_role",person.isEmpty()?"UNKNOWN":person.getFirst().get("role"));}
            else{var person=jdbc.queryForList("SELECT role,owner_id FROM security_console_operator WHERE id=? FOR SHARE",row.get("subject_key"));row.put("current_role",person.isEmpty()?"UNKNOWN":person.getFirst().get("role"));}
            var fresh=baseArgs();fresh.add(ref);
            row.putAll(jdbc.queryForMap(base()+" AND r.ref_id=?",fresh.toArray()));
        }
        return row;
    }
    private void version(Map<String,Object> row,long version){if(version<0||((Number)row.get("version")).longValue()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"会話の状態が変更されました。一覧を再取得してください。");}
    public Map<String,Object> terminate(String ref,long version,boolean all,String reason,String otp){
        String why=ConsoleMembers.reason(reason);var actor=members.require("READ",false);
        if(!direct(actor,target(ref,false)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"この対象は最高管理者への申請が必要です。");
        members.stepUp(otp,"PERSONAL");
        return tx.execute(status->{
            var current=members.require("PERSONAL",true);var row=target(ref,true);
            if(!direct(current,row))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"対象の操作範囲が変更されました。");
            if(row.get("revoked_at")==null)version(row,version);
            long cutoff=all?registry.lockSubject((String)row.get("realm"),(String)row.get("owner_key"),(String)row.get("subject_key")):0;
            // 終了済み参照の再送で、その後の新規ログインまで対象を拡張しない。
            int count=row.get("revoked_at")==null?end(row,all,cutoff,current.operatorId(),"MANUAL"):0;
            members.audit(current.operatorId(),all?"SESSION_REVOKE_ALL":"SESSION_REVOKE",ref,"SUCCESS",why);
            return result(count);
        });
    }
    private int end(Map<String,Object> row,boolean all,long cutoff,String actor,String reason){
        String where=all?"realm=? AND owner_key=? AND subject_key=? AND sequence_no<=?":"ref_id=?";
        var args=new ArrayList<Object>(List.of(Timestamp.valueOf(registry.now()),reason,actor));
        if(all)args.addAll(List.of(row.get("realm"),row.get("owner_key"),row.get("subject_key"),cutoff));else args.add(row.get("ref_id"));
        return jdbc.update("UPDATE security_session_registry SET revoked_at=?,revocation_reason=?,revoked_by=?,version=version+1 WHERE "+where+" AND revoked_at IS NULL",args.toArray());
    }
    private Map<String,Object> result(int count){
        var binding=registry.binding();boolean self=binding!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM security_session_registry WHERE ref_id=? AND revoked_at IS NOT NULL",Integer.class,binding.ref())==1;
        return Map.of("success",true,"ended",count,"selfEnded",self,"message",self?"現在の会話を終了しました。再ログインしてください。":"対象の会話を終了しました。アカウントの再ログインは可能です。");
    }
    public Map<String,Object> request(String ref,long version,boolean all,String reason,String otp){
        String why=ConsoleMembers.reason(reason);var actor=members.require("REVIEW_REQUEST",false);
        if(actor.role().equals("OWNER")||direct(actor,target(ref,false)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"この対象は直接終了操作を使用してください。");
        members.stepUp(otp,"REVIEW_REQUEST");
        return tx.execute(status->{
            var current=members.require("REVIEW_REQUEST",true);var row=target(ref,true);version(row,version);
            if(!"ACTIVE".equals(row.get("state"))||row.get("revoked_at")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"終了対象の会話は有効ではありません。");
            long cutoff=all?registry.lockSubject((String)row.get("realm"),(String)row.get("owner_key"),(String)row.get("subject_key")):((Number)row.get("sequence_no")).longValue();
            jdbc.update("INSERT INTO security_console_review(owner_id,requester_id,operation_code,target_value,reason,session_version,session_sequence) VALUES(?,?,?,?,?,?,?)",settings.getOperatorId(),current.operatorId(),all?"SESSION_REVOKE_ALL":"SESSION_REVOKE",ref,why,version,cutoff);
            members.audit(current.operatorId(),"SESSION_REVIEW_REQUEST",ref,"SUCCESS",why);
            return Map.of("success",true,"selfEnded",false,"message","最高管理者へ会話終了を申請しました。");
        });
    }
    void executeReviewed(Map<String,Object> request,String reason){
        var actor=members.require("OWNER",true);String ref=(String)request.get("target_value");var row=target(ref,true);
        if(request.get("session_version")==null||request.get("session_sequence")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"申請の対象情報を確認してください。");
        if(row.get("revoked_at")==null)version(row,((Number)request.get("session_version")).longValue());
        boolean all="SESSION_REVOKE_ALL".equals(request.get("operation_code"));
        end(row,all,((Number)request.get("session_sequence")).longValue(),actor.operatorId(),"REVIEWED");
        members.audit(actor.operatorId(),all?"SESSION_REVOKE_ALL":"SESSION_REVOKE",ref,"SUCCESS",reason);
    }
}
