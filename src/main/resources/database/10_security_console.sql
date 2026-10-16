-- 独立管理コンソールの認証・操作監査。既存社員・パスワード・役割を変更しない。
USE oa_system;
SET time_zone = '+09:00';
SET @add_frozen = (SELECT IF(COUNT(*)=0,
 'ALTER TABLE security_login_attempt ADD COLUMN frozen INT NOT NULL DEFAULT 0 COMMENT ''パスワード認証の保護状態''',
 'SELECT ''frozen exists''') FROM information_schema.columns WHERE table_schema='oa_system' AND table_name='security_login_attempt' AND column_name='frozen');
PREPARE frozen_statement FROM @add_frozen;
EXECUTE frozen_statement;
DEALLOCATE PREPARE frozen_statement;

CREATE TABLE IF NOT EXISTS security_console_operator (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 login_id VARCHAR(50) NOT NULL,
 failed_attempts INT NOT NULL DEFAULT 0,
 blocked_until DATETIME(6) NULL,
 last_totp_step BIGINT NOT NULL DEFAULT -1,
 otp_failures INT NOT NULL DEFAULT 0,
 otp_blocked_until DATETIME(6) NULL,
 active INT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 delete_flag INT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 CONSTRAINT ck_console_operator_values CHECK (failed_attempts>=0 AND otp_failures>=0 AND active IN(0,1) AND delete_flag IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS security_console_action (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operator_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 operation_code VARCHAR(40) NOT NULL,
 target_type VARCHAR(20) NOT NULL,
 target_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 result VARCHAR(16) NOT NULL,
 reason VARCHAR(300) NULL,
 correlation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 delete_flag INT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 CONSTRAINT fk_console_action_operator FOREIGN KEY(operator_id) REFERENCES security_console_operator(id),
 CONSTRAINT ck_console_action_result CHECK(result IN('SUCCESS','REJECTED')),
 CONSTRAINT ck_console_action_active CHECK(delete_flag=0 AND deleted_at IS NULL),
 INDEX idx_console_action_time(created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
