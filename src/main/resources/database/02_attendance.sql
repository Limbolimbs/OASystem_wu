USE oa_system;

-- 勤怠記録を保存する。ユーザーを無効化しても過去の記録は残す。
CREATE TABLE IF NOT EXISTS attendance_record (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '勤怠記録ID',
    user_id BIGINT NOT NULL COMMENT 'ユーザーID',
    work_date DATE NOT NULL COMMENT '勤務日（日本時間）',
    clock_in_at DATETIME(6) NOT NULL COMMENT '出勤日時（日本時間）',
    clock_out_at DATETIME(6) NULL COMMENT '退勤日時（日本時間）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登録日時',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新日時',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '同時更新制御',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_user_work_date (user_id, work_date),
    KEY idx_attendance_work_date (work_date),
    CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='勤怠記録';

-- 作成結果の確認
SHOW COLUMNS FROM attendance_record;
