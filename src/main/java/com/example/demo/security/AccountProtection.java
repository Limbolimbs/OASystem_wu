package com.example.demo.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** 別プロセスの保護・解除も世代で検証する。失効した会話は解除後も復活しない。 */
@Service
public class AccountProtection {
    public record State(boolean protectedLogin, long version) {}
    private final JdbcTemplate jdbc;
    public AccountProtection(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public State state(Long userId, boolean lock) {
        var rows = jdbc.query("SELECT frozen,protection_version FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?"
                + (lock ? " FOR SHARE" : ""), (rs,n) -> new State(rs.getInt(1)==1, rs.getLong(2)), SecurityRequestContext.hash("user:"+userId));
        return rows.isEmpty() ? new State(false,0) : rows.getFirst();
    }
    public boolean valid(Long userId,long version) {
        State current = state(userId,false); return !current.protectedLogin() && current.version()==version;
    }
    public void requireCurrentMutation() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PortalPrincipal principal) {
            State current = state(principal.userId(),true);
            if (current.protectedLogin() || current.version()!=principal.protectionVersion())
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"認証が失効しました。再ログインしてください。");
        }
    }
}
