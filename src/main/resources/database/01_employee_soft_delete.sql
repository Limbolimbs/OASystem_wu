USE oa_system;

-- ユーザーテーブルに登録日時、更新日時、削除日時を追加する
ALTER TABLE sys_user
    MODIFY COLUMN status INT NOT NULL DEFAULT 1 COMMENT '1:有効 0:無効',
    ADD COLUMN created_at DATETIME NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        COMMENT '登録日時',
    ADD COLUMN updated_at DATETIME NOT NULL
        DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
        COMMENT '更新日時',
    ADD COLUMN deleted_at DATETIME NULL
        COMMENT '削除日時';

-- 状態検索用インデックス
CREATE INDEX idx_sys_user_status
    ON sys_user(status);

-- 変更結果を確認する
SHOW COLUMNS FROM sys_user;

SELECT
    id,
    username,
    real_name,
    department,
    role,
    status,
    created_at,
    updated_at,
    deleted_at
FROM sys_user
ORDER BY id;