-- 10適用後。原始IP/端末宣言は公開鍵で暗号化し、独立管理画面だけで復号する。
USE oa_system;
SET time_zone = '+09:00';
CREATE TABLE IF NOT EXISTS security_access_evidence (
 correlation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 producer VARCHAR(16) NOT NULL,
 key_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 encrypted_detail MEDIUMTEXT CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 delete_flag INT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 CONSTRAINT ck_access_evidence_producer CHECK (producer IN('OA','CONTROL')),
 CONSTRAINT ck_access_evidence_active CHECK(delete_flag=0 AND deleted_at IS NULL),
 INDEX idx_access_evidence_source(source_key,occurred_at),
 INDEX idx_access_evidence_time(occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS security_source_profile (
 source_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 last_seen_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 delete_flag INT NOT NULL DEFAULT 0,
 deleted_at DATETIME(6) NULL,
 CONSTRAINT fk_source_evidence FOREIGN KEY(evidence_id) REFERENCES security_access_evidence(correlation_id),
 CONSTRAINT ck_source_profile_active CHECK(delete_flag=0 AND deleted_at IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
