-- 24時間の累積認証制限と保護会話失効。専用移行ツールが既存列を確認して適用する。
-- 既存の社員、role、パスワード、TOTP、有効状態は変更しない。
ALTER TABLE security_login_attempt ADD COLUMN failure_stage INT NOT NULL DEFAULT 0;
ALTER TABLE security_login_attempt ADD COLUMN protection_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE security_login_attempt ADD COLUMN protected_at DATETIME(6) NULL;
ALTER TABLE security_login_attempt ADD COLUMN protection_reason VARCHAR(32) NULL;
ALTER TABLE security_console_operator ADD COLUMN failure_window_start DATETIME(6) NULL;
ALTER TABLE security_console_operator ADD COLUMN failure_stage INT NOT NULL DEFAULT 0;
ALTER TABLE security_console_operator ADD COLUMN login_protected INT NOT NULL DEFAULT 0;
ALTER TABLE security_console_operator ADD COLUMN protected_at DATETIME(6) NULL;
ALTER TABLE security_console_operator ADD COLUMN protection_reason VARCHAR(32) NULL;
