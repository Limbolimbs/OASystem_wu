package com.example.demo.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoginProtectionService {
    public record Outcome(User user, long retryAfter, boolean protectedLogin, long protectionVersion) {
        public Outcome(User user,long retryAfter) { this(user,retryAfter,false,0); }
        public Outcome(User user,long retryAfter,boolean protectedLogin) { this(user,retryAfter,protectedLogin,0); }
    }
    private record Counter(int attempts, LocalDateTime windowStart, LocalDateTime blockedUntil, boolean frozen, int stage, long version) {
        com.example.security.ProgressiveLoginPolicy.State state() { return new com.example.security.ProgressiveLoginPolicy.State(attempts,windowStart,stage,blockedUntil,frozen); }
    }
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final AuditService audit;
    private final Clock clock;
    private final SecuritySettings settings;
    private final TransactionTemplate transaction;
    public LoginProtectionService(JdbcTemplate jdbc, UserService users, AuditService audit, Clock clock,
            SecuritySettings settings, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.users = users; this.audit = audit; this.clock = clock; this.settings = settings;
        this.transaction = new TransactionTemplate(manager);
    }
    private Counter lock(String scope, String key, LocalDateTime now) {
        jdbc.update("INSERT INTO security_login_attempt (scope,key_hash,attempts,window_start) VALUES (?,?,0,?)"
                + " ON DUPLICATE KEY UPDATE key_hash=key_hash", scope, key, now);
        return jdbc.queryForObject("SELECT attempts,window_start,blocked_until,frozen,failure_stage,protection_version FROM security_login_attempt WHERE scope=? AND key_hash=? FOR UPDATE",
                new RowMapper<Counter>() {
                    @Override
                    public Counter mapRow(ResultSet row, int index) throws SQLException {
                        int attempts = row.getInt("attempts");
                        LocalDateTime windowStart = row.getObject("window_start", LocalDateTime.class);
                        LocalDateTime blockedUntil = row.getObject("blocked_until", LocalDateTime.class);
                        boolean frozen = row.getInt("frozen") == 1;
                        int stage = row.getInt("failure_stage");
                        long version = row.getLong("protection_version");
                        return new Counter(attempts, windowStart, blockedUntil, frozen, stage, version);
                    }
                }, scope, key);
    }
    private void save(String scope, String key, int attempts, LocalDateTime start, LocalDateTime blocked) {
        jdbc.update("UPDATE security_login_attempt SET attempts=?,window_start=?,blocked_until=?,delete_flag=0,deleted_at=NULL WHERE scope=? AND key_hash=?",
                attempts, start, blocked, scope, key);
    }
    private long waitSeconds(LocalDateTime now, LocalDateTime until) {
        return until != null && until.isAfter(now) ? Math.max(1, (Duration.between(now, until).toMillis() + 999) / 1000) : 0;
    }
    public Outcome login(String loginId, String password, String remoteAddress) {
        return authenticate(loginId, password, remoteAddress, "LOGIN");
    }

    /** 本人操作の再認証にも同じ永続的な失敗回数・保護段階を適用する。 */
    public Outcome verifyPassword(User user, String password, String remoteAddress) {
        return authenticate(user.getUsername(), password, remoteAddress, "PASSWORD_VERIFY");
    }

    private Outcome authenticate(String loginId, String password, String remoteAddress, String operation) {
        String id = loginId == null ? "" : loginId.trim();
        // 既存IDはDB照合で内部IDに集約。大文字小文字等で失敗回数を迂回しない。
        User found = id.length() <= 255 ? users.findByUsername(id).orElse(null) : null;
        String canonical = Normalizer.normalize(id, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String account = SecurityRequestContext.hash(found == null ? "unknown:" + canonical : "user:" + found.getId());
        String source = SecurityRequestContext.hash(remoteAddress == null ? "unknown" : remoteAddress);
        return transaction.execute(status -> {
            // DATETIME(6)への丸めで保存値が現在時刻より未来にならないよう桁を揃える。
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo"))
                    .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            // 全処理でSOURCE→ACCOUNTの順にロックし、同時試行・再起動時も回数を保持する。
            Counter from = lock("SOURCE", source, now);
            long sourceWait = waitSeconds(now, from.blockedUntil());
            if (sourceWait > 0) { audit.record(operation + "_BLOCKED", found == null ? null : found.getId(), "SOURCE", null, "REJECTED", null, account); return new Outcome(null, sourceWait); }
            boolean newWindow = !from.windowStart().plus(settings.getSourceWindow()).isAfter(now);
            int sourceAttempts = newWindow ? 1 : from.attempts() + 1;
            LocalDateTime sourceStart = newWindow ? now : from.windowStart();
            // 旧時区の値でも回数は保持する。未来の送信元窓だけを現在時刻から数える。
            // 有効な制限は上のsourceWaitで拒否済みであり、ここでは解除しない。
            if (sourceStart.isAfter(now)) {
                sourceStart = now;
            }
            LocalDateTime sourceBlocked = sourceAttempts > settings.getSourceLimit() ? sourceStart.plus(settings.getSourceWindow()) : null;
            save("SOURCE", source, sourceAttempts, sourceStart, sourceBlocked);
            if (sourceBlocked != null) { audit.record(operation + "_BLOCKED", found == null ? null : found.getId(), "SOURCE", null, "REJECTED", null, account); return new Outcome(null, waitSeconds(now, sourceBlocked)); }
            Counter counter = lock("ACCOUNT", account, now);
            if(counter.frozen()) {
                audit.record(operation + "_PROTECTED",found==null?null:found.getId(),"ACCOUNT",found==null?null:found.getId().toString(),"REJECTED",null,account);
                return new Outcome(null,0,true);
            }
            long wait = waitSeconds(now, counter.blockedUntil());
            if (wait > 0) { audit.record(operation + "_BLOCKED", found == null ? null : found.getId(), "ACCOUNT", null, "REJECTED", null, account); return new Outcome(null, wait); }
            var policy = settings.loginPolicy();
            var state = policy.current(counter.state(),now);
            User authenticated = users.findLoginUser(id.length() <= 255 ? id : "", password != null && password.length() <= 1024 ? password : "");
            if (authenticated != null) {
                save("ACCOUNT", account, 0, now, null);
                jdbc.update("UPDATE security_login_attempt SET failure_stage=0,protected_at=NULL,protection_reason=NULL WHERE scope='ACCOUNT' AND key_hash=?",account);
                audit.record(operation + "_SUCCESS", authenticated.getId(), "ACCOUNT", authenticated.getId().toString(), "SUCCESS", null, account);
                return new Outcome(authenticated, 0, false, counter.version());
            }
            var next = policy.failed(state,now);
            save("ACCOUNT", account, next.failures(), next.windowStart(), next.blockedUntil());
            jdbc.update("UPDATE security_login_attempt SET failure_stage=?,frozen=?,protected_at=?,protection_reason=?,protection_version=protection_version+? WHERE scope='ACCOUNT' AND key_hash=?",
                    next.stage(),next.protectedLogin()?1:0,next.protectedLogin()?now:null,
                    next.protectedLogin()?"AUTH_FAILURE_LIMIT":null,next.protectedLogin()?1:0,account);
            audit.record(operation + (next.protectedLogin()?"_PROTECTED":next.blockedUntil()!=null?"_BLOCKED":"_FAILED"),
                    found==null?null:found.getId(),"ACCOUNT",found==null?null:found.getId().toString(),"REJECTED",null,account);
            return new Outcome(null, policy.retryAfter(next,now),next.protectedLogin());
        });
    }
}
