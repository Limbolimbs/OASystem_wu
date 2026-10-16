package com.example.demo.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 明文を受け入れる互換モードに退避せず、移行漏れを起動時に検出する。 */
@Component
public class PasswordStorageCheck implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public PasswordStorageCheck(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void run(ApplicationArguments arguments) {
        Integer coreTables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('security_login_attempt','security_audit_log','security_management_guard')", Integer.class);
        if (coreTables == null || coreTables != 3) throw new IllegalStateException("共通安全テーブルが必要です。09_session_login_audit.sqlを適用してください。");
        boolean valid = jdbc.query("SELECT password FROM sys_user", (rows, index) -> PasswordHashes.isSupported(rows.getString(1)))
                .stream().allMatch(Boolean::booleanValue);
        if (!valid) throw new IllegalStateException("パスワード移行が必要です。アプリを停止し、password-migration.ps1を実行してください。");
    }
}
