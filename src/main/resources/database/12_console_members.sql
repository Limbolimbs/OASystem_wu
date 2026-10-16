-- 独立セキュリティ管理：個人認証・管理インスタンス別権限。
-- 既存管理ID/パスワード/TOTP及び社員情報は変更しない。
USE oa_system;
SET time_zone = '+09:00';

ALTER TABLE security_console_operator
 ADD COLUMN owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'OWNER',
 ADD COLUMN display_name VARCHAR(50) NULL,
 ADD COLUMN password_hash VARCHAR(255) NULL,
 ADD COLUMN totp_secret_enc VARCHAR(512) CHARACTER SET ascii NULL,
 ADD COLUMN enrollment_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 ADD COLUMN invitation_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN enrollment_until DATETIME(6) NULL,
 ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0,
 ADD UNIQUE KEY uq_console_member_login(owner_id,login_id),
 ADD CONSTRAINT ck_console_member_role CHECK(role IN('OWNER','OBSERVER','OPERATOR')),
 ADD CONSTRAINT ck_console_member_enrollment CHECK(enrollment_state IN('INVITED','SETUP','PENDING','ACTIVE','DISABLED','DELETED'));

CREATE TABLE IF NOT EXISTS security_console_entry_limit (
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 window_start DATETIME(6) NOT NULL,
 blocked_until DATETIME(6) NULL,
 PRIMARY KEY(owner_id,source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 技術的な試行制限状態も共通日時・削除フラグを保持する。通常画面から削除しない。
ALTER TABLE security_console_entry_limit
 ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 ADD COLUMN delete_flag INT NOT NULL DEFAULT 0,
 ADD COLUMN deleted_at DATETIME(6) NULL,
 ADD CONSTRAINT ck_console_entry_active CHECK(delete_flag=0 AND deleted_at IS NULL);

CREATE TABLE IF NOT EXISTS security_console_review (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 requester_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_code VARCHAR(24) NOT NULL,
 target_value VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 minutes INT NULL,
 reason VARCHAR(300) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 reviewer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 review_reason VARCHAR(300) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 delete_flag INT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 CONSTRAINT fk_console_review_requester FOREIGN KEY(requester_id) REFERENCES security_console_operator(id),
 CONSTRAINT ck_console_review_state CHECK(state IN('PENDING','APPROVED','REJECTED')),
 CONSTRAINT ck_console_review_operation CHECK(operation_code IN('ACCOUNT_PROTECT','ACCOUNT_RELEASE','SOURCE_RESTRICT','SOURCE_RELEASE')),
 CONSTRAINT ck_console_review_active CHECK(delete_flag=0 AND deleted_at IS NULL),
 INDEX idx_console_review_owner(owner_id,state,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
