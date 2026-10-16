-- 05: 日時・論理削除の共通化（MySQL 8.4 / 01～04適用後）
-- アプリを停止し、バックアップと接続先を確認してから実行する。
-- このSQLはクラウドに自動適用しない。ALTERは1回限り。DDLはロールバック不可。
USE oa_system;
SET SESSION time_zone = '+09:00';

-- 【事前確認】下記2つのSELECTだけを先に実行する。どちらも0件の場合のみ先へ進む。
-- 不整合は自動修正せず、既存データの意味を確認する。
SELECT id, status, deleted_at FROM sys_user
WHERE status NOT IN (0, 1) OR (status = 1 AND deleted_at IS NOT NULL);
SELECT id, sales_month FROM sales_data
WHERE sales_month IS NULL OR NOT REGEXP_LIKE(sales_month, '^[2-9][0-9]{3}-(0[1-9]|1[0-2])$', 'c');

-- 【変更】
-- delete_flag: 0=有効（未削除）、1=無効（論理削除）。
-- deleted_at: 無効化した日本時間。復元時はNULL。既存行・主キー・外部キーは保持する。
-- 日付はDATE、標準時刻はTIME(0)、日時はDATETIME(6)。
-- 既存日時を加減算しない（旧データのタイムゾーンを推測しない）。

ALTER TABLE sys_user
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    MODIFY COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時（日本時間）',
    ADD CONSTRAINT ck_sys_user_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE department_master
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時（日本時間）',
    ADD CONSTRAINT ck_department_master_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE sales_data
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時（日本時間）',
    ADD CONSTRAINT ck_sales_data_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE attendance_record
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN clock_in_at DATETIME(6) NOT NULL COMMENT '業務日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時（日本時間）',
    MODIFY COLUMN clock_out_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    ADD CONSTRAINT ck_attendance_record_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE attendance_policy
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時（日本時間）',
    MODIFY COLUMN standard_start TIME(0) NOT NULL COMMENT '標準始業時刻',
    MODIFY COLUMN standard_end TIME(0) NOT NULL COMMENT '標準終業時刻',
    ADD CONSTRAINT ck_attendance_policy_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE attendance_policy_history
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN standard_start TIME(0) NOT NULL COMMENT '標準始業時刻',
    MODIFY COLUMN standard_end TIME(0) NOT NULL COMMENT '標準終業時刻',
    ADD CONSTRAINT ck_attendance_policy_history_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE company_holiday
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    ADD CONSTRAINT ck_company_holiday_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE work_schedule
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時（日本時間）',
    MODIFY COLUMN planned_start_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    MODIFY COLUMN planned_end_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    ADD CONSTRAINT ck_work_schedule_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE attendance_request
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時（日本時間）',
    MODIFY COLUMN requested_clock_in_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    MODIFY COLUMN requested_clock_out_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    MODIFY COLUMN requested_overtime_end_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    MODIFY COLUMN reviewed_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    ADD CONSTRAINT ck_attendance_request_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE attendance_break
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN started_at DATETIME(6) NOT NULL COMMENT '業務日時（日本時間）',
    MODIFY COLUMN ended_at DATETIME(6) NULL COMMENT '業務日時（日本時間）',
    ADD CONSTRAINT ck_attendance_break_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE attendance_event
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN event_at DATETIME(6) NOT NULL COMMENT '業務日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    ADD CONSTRAINT ck_attendance_event_delete_flag CHECK (delete_flag IN (0, 1));

ALTER TABLE attendance_audit_log
    ADD COLUMN delete_flag INT NOT NULL DEFAULT 0 COMMENT '0:有効（未削除） 1:無効（論理削除）',
    ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '無効化日時（日本時間）',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時（日本時間）',
    ADD CONSTRAINT ck_attendance_audit_log_delete_flag CHECK (delete_flag IN (0, 1));

-- 有効・無効の既存状態を移行する。更新日時は移行操作で上書きしない。
UPDATE sys_user SET delete_flag = 1,
    deleted_at = COALESCE(deleted_at, updated_at, created_at, CURRENT_TIMESTAMP(6)),
    updated_at = updated_at WHERE status = 0;
UPDATE department_master SET delete_flag = 1,
    deleted_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP(6)),
    updated_at = updated_at WHERE status = 0;
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_active_delete
    CHECK ((status = 1 AND delete_flag = 0) OR (status = 0 AND delete_flag = 1));
ALTER TABLE department_master ADD CONSTRAINT ck_department_active_delete
    CHECK ((status = 1 AND delete_flag = 0) OR (status = 0 AND delete_flag = 1));
ALTER TABLE sales_data ADD CONSTRAINT ck_sales_month_format
    CHECK (REGEXP_LIKE(sales_month, '^[2-9][0-9]{3}-(0[1-9]|1[0-2])$', 'c'));

-- 通常検索用。主キー・一意制約は変更しない（無効データの二重登録を防ぐ）。
CREATE INDEX idx_user_active_delete ON sys_user (status, delete_flag, id);
CREATE INDEX idx_department_active_delete ON department_master (status, delete_flag, id);
CREATE INDEX idx_sales_active_month ON sales_data (delete_flag, sales_month, department);
CREATE INDEX idx_record_active_user ON attendance_record (user_id, delete_flag, clock_out_at, work_date);
CREATE INDEX idx_schedule_active_date ON work_schedule (work_date, delete_flag);
CREATE INDEX idx_request_active_status ON attendance_request (status, delete_flag, created_at);
CREATE INDEX idx_break_active_record ON attendance_break (attendance_record_id, delete_flag);

-- 【適用結果】全12テーブルにdelete_flag/deleted_atがあること、DATETIME精度が6であることを確認。
SELECT table_name, column_name, column_type, datetime_precision
FROM information_schema.columns
WHERE table_schema = DATABASE() AND (
    column_name IN ('delete_flag', 'deleted_at') OR data_type IN ('date', 'datetime', 'timestamp', 'time'))
ORDER BY table_name, ordinal_position;
SELECT status, delete_flag, COUNT(*) AS record_count FROM sys_user GROUP BY status, delete_flag;
SELECT status, delete_flag, COUNT(*) AS record_count FROM department_master GROUP BY status, delete_flag;
-- 勤怠申請のstatusは審査状態。CANCELLED（取り下げ）も履歴に残し、delete_flagは0のまま。
-- 打刻原本・監査ログも共通列を持つが、通常機能では無効化・物理削除しない。
