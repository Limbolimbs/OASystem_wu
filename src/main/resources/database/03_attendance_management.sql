USE oa_system;

-- 実行順序: 01_employee_soft_delete.sql、02_attendance.sql、03_attendance_management.sql
-- 日時は日本時間（Asia/Tokyo）として保存する。

-- 会社全体の既定勤務ルール。曜日ビットは月=1、火=2、水=4、木=8、金=16、土=32、日=64。
CREATE TABLE IF NOT EXISTS attendance_policy (
    id BIGINT NOT NULL PRIMARY KEY,
    standard_start TIME NOT NULL,
    standard_end TIME NOT NULL,
    break_minutes INT NOT NULL DEFAULT 60,
    late_grace_minutes INT NOT NULL DEFAULT 0,
    early_grace_minutes INT NOT NULL DEFAULT 0,
    overtime_threshold_minutes INT NOT NULL DEFAULT 0,
    work_days_mask INT NOT NULL DEFAULT 31,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='既定勤務ルール';

INSERT INTO attendance_policy (
    id, standard_start, standard_end, break_minutes,
    late_grace_minutes, early_grace_minutes, overtime_threshold_minutes, work_days_mask
) VALUES (1, '09:00:00', '18:00:00', 60, 0, 0, 0, 31)
ON DUPLICATE KEY UPDATE id = id;

-- ルールの適用履歴。変更は翌日から有効にし、過去の判定を変えない。
CREATE TABLE IF NOT EXISTS attendance_policy_history (
    effective_from DATE NOT NULL PRIMARY KEY,
    standard_start TIME NOT NULL,
    standard_end TIME NOT NULL,
    break_minutes INT NOT NULL,
    late_grace_minutes INT NOT NULL,
    early_grace_minutes INT NOT NULL,
    overtime_threshold_minutes INT NOT NULL,
    work_days_mask INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='勤務ルール適用履歴';

INSERT INTO attendance_policy_history (
    effective_from, standard_start, standard_end, break_minutes,
    late_grace_minutes, early_grace_minutes, overtime_threshold_minutes, work_days_mask
)
SELECT '1900-01-01', standard_start, standard_end, break_minutes,
       late_grace_minutes, early_grace_minutes, overtime_threshold_minutes, work_days_mask
FROM attendance_policy WHERE id = 1
ON DUPLICATE KEY UPDATE effective_from = effective_from;

-- 祝日・会社休日は管理者が登録する。国民の祝日の自動判定は行わない。
CREATE TABLE IF NOT EXISTS company_holiday (
    holiday_date DATE NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会社休日';

-- 従業員別の勤務予定。終了日時は翌日を指定できる。
CREATE TABLE IF NOT EXISTS work_schedule (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    planned_start_at DATETIME(6) NULL,
    planned_end_at DATETIME(6) NULL,
    break_minutes INT NOT NULL DEFAULT 60,
    day_type VARCHAR(20) NOT NULL,
    work_mode VARCHAR(20) NOT NULL DEFAULT 'OFFICE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_work_schedule_user_date (user_id, work_date),
    KEY idx_work_schedule_date (work_date),
    CONSTRAINT fk_work_schedule_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='勤務予定';

-- 勤怠修正、外勤、在宅勤務、残業、休日出勤、休暇の申請を保持する。
CREATE TABLE IF NOT EXISTS attendance_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_type VARCHAR(24) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    requested_clock_in_at DATETIME(6) NULL,
    requested_clock_out_at DATETIME(6) NULL,
    requested_overtime_end_at DATETIME(6) NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewer_id BIGINT NULL,
    review_comment VARCHAR(1000) NULL,
    reviewed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    KEY idx_attendance_request_user_date (user_id, start_date),
    KEY idx_attendance_request_status (status, created_at),
    CONSTRAINT fk_attendance_request_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_attendance_request_reviewer FOREIGN KEY (reviewer_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='勤怠申請';

-- 実休憩を記録する。未終了の休憩がある間は退勤できない。
CREATE TABLE IF NOT EXISTS attendance_break (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attendance_record_id BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    KEY idx_attendance_break_record (attendance_record_id),
    CONSTRAINT fk_attendance_break_record FOREIGN KEY (attendance_record_id)
        REFERENCES attendance_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='休憩記録';

-- 打刻・修正は追記専用の履歴として保存する。
CREATE TABLE IF NOT EXISTS attendance_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attendance_record_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    event_at DATETIME(6) NOT NULL,
    work_mode VARCHAR(20) NULL,
    note VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_attendance_event_record (attendance_record_id, created_at),
    CONSTRAINT fk_attendance_event_record FOREIGN KEY (attendance_record_id)
        REFERENCES attendance_record (id),
    CONSTRAINT fk_attendance_event_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_attendance_event_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打刻・修正履歴';

-- 管理者によるルール・予定変更を追跡する。
CREATE TABLE IF NOT EXISTS attendance_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_user_id BIGINT NOT NULL,
    action_name VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    old_value TEXT NULL,
    new_value TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_attendance_audit_created (created_at),
    CONSTRAINT fk_attendance_audit_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='勤怠管理監査履歴';
