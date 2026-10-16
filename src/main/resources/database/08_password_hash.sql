-- パスワード移行ツールの適用・照合後のみ実行する。既存ログインIDは変更しない。
-- Argon2idはJava側で生成する。SQLのSHA2/MD5で代用しない。
USE oa_system;

-- 必ず0件であることを確認する。旧平文が残る場合、ALTERを実行しない。
SELECT COUNT(*) AS unconverted_passwords
FROM sys_user
WHERE NOT REGEXP_LIKE(password,
    '^[{]argon2[}][$]argon2id[$]v=19[$]m=19456,t=2,p=1[$][A-Za-z0-9+/]{22}[$][A-Za-z0-9+/]{43}$', 'c');

-- 一度だけ適用。既存列サイズVARCHAR(255)はハッシュ格納に十分なため維持する。
ALTER TABLE sys_user
    ADD CONSTRAINT ck_sys_user_password_hash CHECK (REGEXP_LIKE(password,
        '^[{]argon2[}][$]argon2id[$]v=19[$]m=19456,t=2,p=1[$][A-Za-z0-9+/]{22}[$][A-Za-z0-9+/]{43}$', 'c'));

SELECT constraint_name, enforced
FROM information_schema.table_constraints
WHERE constraint_schema = 'oa_system' AND table_name = 'sys_user'
    AND constraint_name = 'ck_sys_user_password_hash';
