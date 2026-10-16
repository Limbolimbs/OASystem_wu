USE oa_system;

-- 適用前にバックアップを取得する。次の確認SQLが0件であることを確認する。
-- 重複がある場合は、内容を確認してから別途整理する（このSQLでは削除・合算しない）。
SELECT sales_month, department, COUNT(*) AS duplicate_count
FROM sales_data GROUP BY sales_month, department HAVING COUNT(*) > 1;

-- ここからのALTERは1回だけ実行する。途中で失敗した場合は列・索引を確認する。
CREATE TABLE IF NOT EXISTS department_master (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    sales_enabled BIT NOT NULL DEFAULT b'0' COMMENT '営業実績登録を許可する部署',
    status INT NOT NULL DEFAULT 1 COMMENT '1:有効 0:無効',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_department_code UNIQUE (code),
    CONSTRAINT uk_department_name UNIQUE (name),
    CONSTRAINT ck_department_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 既存の所属・集計部署を引き継ぐ。名称だけから管理者権限は付与しない。
INSERT INTO department_master (code, name, sales_enabled)
SELECT CONCAT('AUTO_', LEFT(SHA2(existing.name, 256), 32)), existing.name,
       IF(existing.name = '営業部', b'1', b'0')
FROM (
    SELECT DISTINCT CONVERT(department USING utf8mb4) COLLATE utf8mb4_unicode_ci AS name
    FROM sys_user WHERE department IS NOT NULL AND TRIM(department) <> ''
    UNION
    SELECT DISTINCT CONVERT(department USING utf8mb4) COLLATE utf8mb4_unicode_ci AS name
    FROM sales_data WHERE department IS NOT NULL AND TRIM(department) <> ''
    UNION SELECT _utf8mb4'営業部' COLLATE utf8mb4_unicode_ci
) AS existing
WHERE NOT EXISTS (SELECT 1 FROM department_master d WHERE d.name = existing.name);

ALTER TABLE sales_data
    ADD COLUMN notes VARCHAR(500) NULL COMMENT '確認メモ',
    ADD COLUMN updated_by BIGINT NULL COMMENT '最終更新ユーザーID（既存実績はNULL）',
    ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登録日時（既存実績は移行日時）',
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新日時',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '楽観ロック';

-- 同じ対象月・部署の二重登録をDBでも防止する。重複があれば失敗し、既存実績は保持する。
CREATE UNIQUE INDEX uk_sales_month_department ON sales_data (sales_month, department);

SHOW COLUMNS FROM department_master;
SHOW COLUMNS FROM sales_data;
SELECT id, code, name, sales_enabled, status FROM department_master ORDER BY id;
