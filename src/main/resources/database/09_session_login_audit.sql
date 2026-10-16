-- 共通セッション・認証試行制限・監査。08適用後、アプリを停止して実行する。
USE oa_system;
SET time_zone = '+09:00';

-- 既存の社員番号・パスワード・状態・業務日時は変更しない。
SET @add_auth_version = (SELECT IF(COUNT(*)=0,
    'ALTER TABLE sys_user ADD COLUMN authentication_version BIGINT NOT NULL DEFAULT 0 COMMENT ''認証状態の世代''',
    'SELECT ''authentication_version exists''')
    FROM information_schema.columns WHERE table_schema='oa_system' AND table_name='sys_user' AND column_name='authentication_version');
PREPARE auth_version_statement FROM @add_auth_version;
EXECUTE auth_version_statement;
DEALLOCATE PREPARE auth_version_statement;

CREATE TABLE IF NOT EXISTS security_management_guard (
    id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    delete_flag INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_security_guard_active CHECK (id=1 AND delete_flag=0 AND deleted_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO security_management_guard (id) VALUES (1) ON DUPLICATE KEY UPDATE id=id;

CREATE TABLE IF NOT EXISTS security_login_attempt (
    scope VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    window_start DATETIME(6) NOT NULL,
    blocked_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    delete_flag INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (scope,key_hash),
    CONSTRAINT ck_security_attempt_scope CHECK (scope IN ('ACCOUNT','SOURCE')),
    CONSTRAINT ck_security_attempt_count CHECK (attempts>=0),
    CONSTRAINT ck_security_attempt_delete CHECK (delete_flag IN (0,1)),
    INDEX idx_security_attempt_blocked (blocked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS security_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(6) NOT NULL,
    actor_id BIGINT NULL,
    operation_code VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(100) NULL,
    result VARCHAR(16) NOT NULL,
    correlation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    subject_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    changes_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    delete_flag INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_security_audit_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT ck_security_audit_result CHECK (result IN ('SUCCESS','REJECTED','FAILED')),
    CONSTRAINT ck_security_audit_active CHECK (delete_flag=0 AND deleted_at IS NULL),
    INDEX idx_security_audit_time (occurred_at,id),
    INDEX idx_security_audit_actor (actor_id,occurred_at),
    INDEX idx_security_audit_request (correlation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SHOW COLUMNS FROM sys_user;
SHOW TABLES LIKE 'security_%';
