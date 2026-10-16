package com.example.demo.security;

import com.example.demo.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
public class AccountSecurityState {
    private final JdbcTemplate jdbc;
    private final SessionLifecycle sessions;
    public AccountSecurityState(JdbcTemplate jdbc, SessionLifecycle sessions) { this.jdbc = jdbc; this.sessions = sessions; }
    public void lockManagement() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("管理更新にはトランザクションが必要です。");
        Long guard = jdbc.queryForObject("SELECT id FROM security_management_guard WHERE id=1 AND delete_flag=0 FOR UPDATE", Long.class);
        if (guard == null) throw new IllegalStateException("管理排他設定がありません。");
    }
    public int activeAdministratorCount() {
        return jdbc.queryForList("SELECT id FROM sys_user WHERE role='ADMIN' AND status=1 AND delete_flag=0 FOR UPDATE", Long.class).size();
    }
    public void revoke(User user) {
        user.setAuthenticationVersion(Math.addExact(user.getAuthenticationVersion(), 1));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { sessions.invalidateAll(user.getId()); }
        });
    }
}
