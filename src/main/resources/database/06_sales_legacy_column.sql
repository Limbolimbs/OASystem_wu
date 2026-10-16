-- 06: 旧名称の顧客数列との互換性（04・05適用後）
-- バックアップ後に実行する。旧列・既存値は削除しない。
-- 現行アプリはcustomer_countを使用する。旧coustomer_countの必須入力のみ解除する。
USE oa_system;

SET @legacy_column_sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'sales_data'
              AND column_name = 'coustomer_count'),
    'ALTER TABLE sales_data MODIFY COLUMN coustomer_count INT NULL DEFAULT NULL COMMENT ''旧顧客数列（互換性のため保持）''',
    'SELECT ''旧顧客数列は存在しないため変更不要'' AS result'
);
PREPARE legacy_column_stmt FROM @legacy_column_sql;
EXECUTE legacy_column_stmt;
DEALLOCATE PREPARE legacy_column_stmt;

-- 新規登録時はcustomer_countのみ指定できる。旧列からの自動転記・集計は行わない。
SHOW COLUMNS FROM sales_data;
