-- 会話参照はCookieと独立したUUID。原始IPは暗号化証跡だけに保持する。
CREATE TABLE IF NOT EXISTS security_session_subject_guard (
 realm VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 subject_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence_no BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 delete_flag INT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 PRIMARY KEY(realm,owner_key,subject_key),
 CONSTRAINT ck_session_guard_realm CHECK(realm IN('OA','CONTROL')),
 CONSTRAINT ck_session_guard_active CHECK(delete_flag=0 AND deleted_at IS NULL AND sequence_no>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS security_session_registry (
 ref_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 realm VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 subject_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence_no BIGINT NOT NULL,
 login_id VARCHAR(255) NOT NULL,
 role VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 auth_version BIGINT NOT NULL,
 protection_version BIGINT NOT NULL DEFAULT 0,
 node_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 runtime_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ip_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 issued_at DATETIME(6) NOT NULL,
 last_active_at DATETIME(6) NOT NULL,
 idle_expires_at DATETIME(6) NOT NULL,
 absolute_expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 revocation_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 revoked_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 delete_flag INT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 CONSTRAINT ck_session_realm CHECK(realm IN('OA','CONTROL')),
 CONSTRAINT ck_session_dates CHECK(issued_at<=last_active_at AND issued_at<absolute_expires_at),
 CONSTRAINT ck_session_active CHECK(delete_flag=0 AND deleted_at IS NULL AND sequence_no>0 AND version>=0),
 INDEX idx_session_subject(realm,owner_key,subject_key,sequence_no),
 INDEX idx_session_scope(realm,owner_key,issued_at,ref_id),
 INDEX idx_session_ip(ip_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE security_console_review ADD COLUMN session_version BIGINT NULL;
ALTER TABLE security_console_review ADD COLUMN session_sequence BIGINT NULL;
ALTER TABLE security_console_review DROP CHECK ck_console_review_operation;
ALTER TABLE security_console_review ADD CONSTRAINT ck_console_review_operation CHECK(operation_code IN('ACCOUNT_PROTECT','ACCOUNT_RELEASE','SOURCE_RESTRICT','SOURCE_RELEASE','SESSION_REVOKE','SESSION_REVOKE_ALL'));
