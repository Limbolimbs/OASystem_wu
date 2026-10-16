package com.example.demo.maintenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** 09の追加DDL専用。既存業務データを変更せず、実行前DDLと照合用ダイジェストを保存する。 */
public final class SecuritySchemaMigration {
    static void validateDestination(String url) {
        try {
            if (url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            var uri = java.net.URI.create(url.substring(5));
            if (uri.getHost() == null || !uri.getHost().endsWith(".aivencloud.com")
                    || uri.getPort() < 1 || uri.getPort() > 65535 || !"/oa_system".equals(uri.getPath())
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() == null)
                throw new IllegalArgumentException();
            String mode = null;
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if ("sslMode".equalsIgnoreCase(key)) {
                    if (mode != null || parts.length != 2) throw new IllegalArgumentException();
                    mode = java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
            if (!Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(mode == null ? "" : mode))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Aivenのoa_system・TLS必須接続だけを許可します。");
        }
    }
    private static String setting(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1); int separator = key.indexOf(':');
            String environment = System.getenv(separator < 0 ? key : key.substring(0, separator));
            value = environment != null ? environment : separator < 0 ? null : key.substring(separator + 1);
        }
        if (value == null || value.isBlank()) throw new IllegalStateException("DB設定が不足しています。");
        return value;
    }
    private static String accountDigest(Connection connection) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT id,username,password,real_name,department,role,status,created_at,updated_at,deleted_at,delete_flag FROM sys_user ORDER BY id")) {
            while (rows.next()) for (int column = 1; column <= 11; column++) {
                String value = rows.getString(column);
                byte[] bytes = (value == null ? "<NULL>" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) throw new IllegalArgumentException("設定ファイル・09SQL・新規バックアップ先を指定してください。");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(arguments[0]), StandardCharsets.UTF_8)) { properties.load(reader); }
        Path sqlFile = Path.of(arguments[1]);
        if (!sqlFile.getFileName().toString().equals("09_session_login_audit.sql")) throw new IllegalArgumentException("09以外は実行できません。");
        Path backup = Path.of(arguments[2]).toAbsolutePath();
        if (!backup.startsWith(Path.of("D:/oa-maintenance")) || Files.exists(backup)) throw new IllegalArgumentException("Dドライブに新規のバックアップ先を指定してください。");
        String url = setting(properties, "spring.datasource.url");
        validateDestination(url);
        try (Connection connection = DriverManager.getConnection(url, setting(properties, "spring.datasource.username"), setting(properties, "spring.datasource.password"))) {
            if (!"oa_system".equals(connection.getCatalog())) throw new IllegalStateException("DB名が不正です。");
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                if (!rows.next() || rows.getString(2).isBlank()) throw new IllegalStateException("TLS接続が必要です。");
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT GET_LOCK('oa_system_security_schema',10)")) {
                if (!rows.next() || rows.getInt(1) != 1) throw new IllegalStateException("他の移行が実行中です。");
            }
            try {
                Files.createDirectory(backup);
                String before = accountDigest(connection);
                StringBuilder ddl = new StringBuilder();
                List<String> tables = new ArrayList<>();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW TABLES")) { while (rows.next()) tables.add(rows.getString(1)); }
                for (String table : tables) {
                    if (!table.matches("[a-z_]+")) throw new IllegalStateException("不正なテーブル名です。");
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) { rows.next(); ddl.append(rows.getString(2)).append(";\n\n"); }
                }
                Files.writeString(backup.resolve("schema-before.sql"), ddl, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
                Files.writeString(backup.resolve("migration.sql"), sql, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                sql = sql.replaceAll("(?m)^\\s*--.*$", "");
                for (String part : sql.split(";")) {
                    String statementSql = part.trim(); if (statementSql.isEmpty()) continue;
                    if (!statementSql.matches("(?is)^(USE oa_system|SET .*|PREPARE auth_version_statement .*|EXECUTE auth_version_statement|DEALLOCATE PREPARE auth_version_statement|CREATE TABLE IF NOT EXISTS security_.*|INSERT INTO security_management_guard .*|SHOW .*?)$"))
                        throw new IllegalStateException("許可されていないSQL文です。");
                    try (var statement = connection.createStatement()) { statement.execute(statementSql); }
                }
                String after = accountDigest(connection);
                if (!before.equals(after)) throw new IllegalStateException("既存社員データの一致確認に失敗しました。");
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oa_system' AND table_name IN ('security_management_guard','security_login_attempt','security_audit_log')")) {
                    rows.next(); if (rows.getInt(1) != 3) throw new IllegalStateException("追加テーブルが不足しています。");
                }
                Files.writeString(backup.resolve("receipt.txt"), "migration=09\nexisting-account-data=unchanged\nsecurity-tables=3\naccount-digest=" + after + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                System.out.println("09追加DDLを適用しました。共通セキュリティ3テーブル・認証世代列を確認。既存社員データ変更なし。");
            } finally { try (var statement = connection.createStatement()) { statement.execute("SELECT RELEASE_LOCK('oa_system_security_schema')"); } }
        } catch (SQLException exception) {
            System.err.println("DB移行失敗 SQLState=" + exception.getSQLState() + " code=" + exception.getErrorCode()); System.exit(1);
        }
    }
}
