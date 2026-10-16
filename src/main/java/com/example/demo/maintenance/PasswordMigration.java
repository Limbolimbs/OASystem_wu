package com.example.demo.maintenance;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.example.demo.security.PasswordHashes;

/** 停止中のDBに対する明示的な保守ツール。起動時の自動移行は行わない。 */
public final class PasswordMigration {
    public static final String SQL_PATTERN = "^[{]argon2[}][$]argon2id[$]v=19[$]m=19456,t=2,p=1[$][A-Za-z0-9+/]{22}[$][A-Za-z0-9+/]{43}$";
    private static final byte[] AAD = "oa_system:sys_user:password:migration-v1".getBytes(StandardCharsets.UTF_8);
    public record Credential(Long id, String before, String after) {}

    private PasswordMigration() {}

    public static byte[] seal(List<Credential> rows, byte[] key) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(1); output.writeInt(rows.size());
            for (Credential row : rows) { output.writeLong(row.id()); output.writeUTF(row.before()); output.writeUTF(row.after()); }
        }
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD);
        byte[] encoded = cipher.doFinal(buffer.toByteArray());
        return java.nio.ByteBuffer.allocate(nonce.length + encoded.length).put(nonce).put(encoded).array();
    }

    public static List<Credential> open(byte[] data, byte[] key) throws Exception {
        if (data.length < 28) throw new IllegalArgumentException("バックアップ形式が不正です。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
        cipher.updateAAD(AAD);
        byte[] plain = cipher.doFinal(Arrays.copyOfRange(data, 12, data.length));
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain))) {
            if (input.readInt() != 1) throw new IllegalArgumentException("バックアップ版が不正です。");
            int count = input.readInt();
            if (count < 0 || count > 1000000) throw new IllegalArgumentException("バックアップ件数が不正です。");
            List<Credential> rows = new ArrayList<>();
            for (int index = 0; index < count; index++) rows.add(new Credential(input.readLong(), input.readUTF(), input.readUTF()));
            if (input.available() != 0) throw new IllegalArgumentException("バックアップに余分な情報があります。");
            return rows;
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    private static byte[] key() {
        String value = System.getenv("OA_MIGRATION_KEY");
        if (value == null) throw new IllegalArgumentException("バックアップ鍵が設定されていません。");
        byte[] key = Base64.getDecoder().decode(value);
        if (key.length != 32) throw new IllegalArgumentException("バックアップ鍵の長さが不正です。");
        return key;
    }

    private static String setting(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String text = value.substring(2, value.length() - 1); int separator = text.indexOf(':');
            String environment = System.getenv(separator < 0 ? text : text.substring(0, separator));
            value = environment != null ? environment : separator < 0 ? null : text.substring(separator + 1);
        }
        if (value == null || value.isBlank()) throw new IllegalArgumentException("DB接続設定が不足しています。");
        return value;
    }

    private static List<Credential> read(Connection connection, boolean lock) throws SQLException {
        List<Credential> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT id,password FROM sys_user ORDER BY id" + (lock ? " FOR UPDATE" : ""))) {
            while (result.next()) rows.add(new Credential(result.getLong(1), result.getString(2), ""));
        }
        return rows;
    }

    private static boolean constraintExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='oa_system'"
                + " AND table_name='sys_user' AND constraint_name='ck_sys_user_password_hash' AND enforced='YES'")) {
            result.next(); return result.getInt(1) == 1;
        }
    }

    private static void addConstraint(Connection connection) throws SQLException {
        if (constraintExists(connection)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_password_hash CHECK (REGEXP_LIKE(password, '" + SQL_PATTERN + "', 'c'))");
        }
    }

    private static void replace(Connection connection, List<Credential> rows, boolean restore) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE sys_user SET password=?,updated_at=updated_at WHERE id=? AND BINARY password=BINARY ?")) {
            for (Credential row : rows) {
                statement.setString(1, restore ? row.before() : row.after()); statement.setLong(2, row.id());
                statement.setString(3, restore ? row.after() : row.before());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("パスワードが他の処理で変更されています。上書きしません。");
            }
        }
    }

    private static void migrate(Connection connection, Path backup) throws Exception {
        connection.setAutoCommit(false);
        try {
            var encoder = PasswordHashes.encoder();
            List<Credential> updates = new ArrayList<>();
            for (Credential row : read(connection, true)) {
                if (PasswordHashes.isSupported(row.before())) continue;
                if (row.before() == null || row.before().startsWith("{argon2}") || row.before().startsWith("$argon2"))
                    throw new IllegalStateException("不明なハッシュ形式があります。自動で再ハッシュしません。");
                String encoded = encoder.encode(row.before());
                if (!PasswordHashes.isSupported(encoded) || !encoder.matches(row.before(), encoded))
                    throw new IllegalStateException("ハッシュ生成後の照合に失敗しました。");
                updates.add(new Credential(row.id(), row.before(), encoded));
            }
            if (!updates.isEmpty()) {
                byte[] backupKey = key();
                try {
                    Files.write(backup, seal(updates, backupKey), StandardOpenOption.CREATE_NEW);
                    if (!updates.equals(open(Files.readAllBytes(backup), backupKey)))
                        throw new IllegalStateException("暗号化バックアップの復号検証に失敗しました。");
                } finally { Arrays.fill(backupKey, (byte) 0); }
                replace(connection, updates, false);
            }
            if (read(connection, false).stream().anyMatch(row -> !PasswordHashes.isSupported(row.before())))
                throw new IllegalStateException("未移行のパスワードがあります。");
            connection.commit();
            System.out.println("移行コミット件数=" + updates.size() + "（社員番号・状態・業務日時は変更なし）");
        } catch (Exception exception) { connection.rollback(); throw exception; }
        finally { connection.setAutoCommit(true); }
        // DDLはMySQLで自動コミットされるため、データ移行の後に別工程で適用する。
        addConstraint(connection);
        System.out.println("ハッシュ形式CHECK制約=適用済み");
    }

    private static void restore(Connection connection, Path backup) throws Exception {
        byte[] backupKey = key();
        List<Credential> rows;
        try { rows = open(Files.readAllBytes(backup), backupKey); }
        finally { Arrays.fill(backupKey, (byte) 0); }
        Map<Long, String> current = new HashMap<>(); read(connection, false).forEach(row -> current.put(row.id(), row.before()));
        if (rows.stream().anyMatch(row -> !row.after().equals(current.get(row.id()))))
            throw new IllegalStateException("移行後に変更された認証情報があります。復旧は中止しました。");
        if (constraintExists(connection)) {
            try (Statement statement = connection.createStatement()) { statement.execute("ALTER TABLE sys_user DROP CHECK ck_sys_user_password_hash"); }
        }
        connection.setAutoCommit(false);
        try { replace(connection, rows, true); connection.commit(); }
        catch (Exception exception) { connection.rollback(); addConstraint(connection); throw exception; }
        finally { connection.setAutoCommit(true); }
        System.out.println("旧版用の認証情報復旧件数=" + rows.size() + "。新版は再移行まで起動しないでください。");
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 || !Set.of("check", "migrate", "restore").contains(arguments[1]))
            throw new IllegalArgumentException("設定ファイル・check/migrate/restore・暗号化バックアップパスを指定してください。");
        Properties configuration = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(arguments[0]), StandardCharsets.UTF_8)) { configuration.load(reader); }
        String url = setting(configuration, "spring.datasource.url");
        if (!url.matches("jdbc:mysql://[^/]+/oa_system(?:\\?.*)?")) throw new IllegalArgumentException("接続先DBはoa_systemである必要があります。");
        Properties credentials = new Properties();
        credentials.setProperty("user", setting(configuration, "spring.datasource.username"));
        credentials.setProperty("password", setting(configuration, "spring.datasource.password"));
        credentials.setProperty("connectTimeout", "10000"); credentials.setProperty("socketTimeout", "30000");
        try (Connection connection = DriverManager.getConnection(url, credentials)) {
            if (!"oa_system".equals(connection.getCatalog())) throw new IllegalStateException("接続先が不正です。");
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                if (!rows.next() || rows.getString(2).isBlank()) throw new IllegalStateException("TLS接続が必要です。");
            }
            if (arguments[1].equals("check")) {
                connection.setReadOnly(true);
                var rows = read(connection, false);
                long supported = rows.stream().filter(row -> PasswordHashes.isSupported(row.before())).count();
                System.out.println("社員件数=" + rows.size() + " / Argon2id=" + supported + " / 要移行=" + (rows.size() - supported));
                System.out.println("ハッシュCHECK制約=" + constraintExists(connection)); return;
            }
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT GET_LOCK('oa_system_password_migration',10)")) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("移行処理が実行中です。");
            }
            try {
                if (arguments[1].equals("migrate")) migrate(connection, Path.of(arguments[2]));
                else restore(connection, Path.of(arguments[2]));
            } finally { try (Statement statement = connection.createStatement()) { statement.execute("SELECT RELEASE_LOCK('oa_system_password_migration')"); } }
        } catch (SQLException exception) {
            System.err.println("DB処理に失敗しました。SQLState=" + exception.getSQLState() + " / code=" + exception.getErrorCode());
            System.exit(1);
        }
    }
}
