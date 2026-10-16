-- お知らせ管理（既存データ・既存テーブルは変更しない）
-- 接続先oa_systemを確認してから実行する。日時は日本時間のDATETIME(6)。
USE oa_system;
SET time_zone = '+09:00';
CREATE TABLE IF NOT EXISTS notice (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(120) NOT NULL,
    content VARCHAR(10000) NOT NULL,
    category VARCHAR(30) NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    delete_flag INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notice_author FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT fk_notice_updater FOREIGN KEY (updated_by) REFERENCES sys_user(id),
    CONSTRAINT ck_notice_status CHECK (status IN ('DRAFT','PUBLISHED')),
    CONSTRAINT ck_notice_priority CHECK (priority IN ('NORMAL','IMPORTANT')),
    CONSTRAINT ck_notice_delete CHECK (delete_flag IN (0,1)),
    INDEX idx_notice_visible (delete_flag,status,published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS notice_read (
    id BIGINT NOT NULL AUTO_INCREMENT,
    notice_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    read_version BIGINT NOT NULL,
    read_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    delete_flag INT NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notice_read_user UNIQUE (notice_id,user_id),
    CONSTRAINT fk_notice_read_notice FOREIGN KEY (notice_id) REFERENCES notice(id),
    CONSTRAINT fk_notice_read_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_notice_read_delete CHECK (delete_flag IN (0,1)),
    INDEX idx_notice_read_user (user_id,delete_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SHOW COLUMNS FROM notice;
SHOW COLUMNS FROM notice_read;
