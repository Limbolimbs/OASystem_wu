package com.example.control;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import com.example.security.ProgressiveLoginPolicy;
import org.springframework.jdbc.core.JdbcTemplate;

/** 管理認証の段階と失効世代。ログインと操作確認に同じ累計を適用する。 */
final class ConsoleLoginState {
    private ConsoleLoginState() {}
    static ProgressiveLoginPolicy.State read(Map<String,Object> row){
        return new ProgressiveLoginPolicy.State((int)ConsoleMembers.number(row,"failed_attempts"),
                ConsoleMembers.time(row.get("failure_window_start")),(int)ConsoleMembers.number(row,"failure_stage"),
                ConsoleMembers.time(row.get("blocked_until")),ConsoleMembers.number(row,"login_protected")==1);
    }
    static ProgressiveLoginPolicy.State failure(JdbcTemplate jdbc,OperatorSettings settings,Map<String,Object> row,LocalDateTime now){
        var next=settings.loginPolicy().failed(read(row),now);
        jdbc.update("UPDATE security_console_operator SET failed_attempts=?,failure_window_start=?,failure_stage=?,blocked_until=?,login_protected=?,protected_at=?,protection_reason=?,auth_version=auth_version+? WHERE id=?",
                next.failures(),Timestamp.valueOf(next.windowStart()),next.stage(),next.blockedUntil()==null?null:Timestamp.valueOf(next.blockedUntil()),
                next.protectedLogin()?1:0,next.protectedLogin()?Timestamp.valueOf(now):null,next.protectedLogin()?"AUTH_FAILURE_LIMIT":null,
                next.protectedLogin()&&!read(row).protectedLogin()?1:0,row.get("id"));
        return next;
    }
    static void success(JdbcTemplate jdbc,String id,long step,LocalDateTime now){
        jdbc.update("UPDATE security_console_operator SET failed_attempts=0,failure_window_start=?,failure_stage=0,blocked_until=NULL,otp_failures=0,otp_blocked_until=NULL,last_totp_step=? WHERE id=?",Timestamp.valueOf(now),step,id);
    }
}
