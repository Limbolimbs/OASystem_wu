package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import com.example.demo.maintenance.PasswordMigration;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.PasswordHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 試験資格情報の暗号化往復と実HTTP。実社員の移行バックアップ・鍵は使用しない。 */
@EnabledIfEnvironmentVariable(named = "OA_ACCEPTANCE_TEST", matches = "true")
class IsolatedSecurityIntegrationTest extends com.example.demo.acceptance.IsolatedIntegrationSupport {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired DataSource source;
    private String base() { return "http://127.0.0.1:" + port; }
    private String csrf(String html) {
        var matcher = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), "共通CSRF metaがありません。"); return matcher.group(1);
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient client, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded").header("X-Requested-With", "XMLHttpRequest")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("X-CSRF-TOKEN", token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Test void existingCredentialsLoginWithRealCsrfAndLogoutInvalidatesTheSession() throws Exception {
        byte[] key = new byte[32];new java.security.SecureRandom().nextBytes(key);
        List<PasswordMigration.Credential> fixtures=users.findAll().stream().map(user ->
            new PasswordMigration.Credential(user.getId(),com.example.demo.acceptance.IsolatedDatabase.ACCOUNT_PASSWORD,user.getPassword())).toList();
        List<PasswordMigration.Credential> records;
        try { records = PasswordMigration.open(PasswordMigration.seal(fixtures,key), key); }
        finally { Arrays.fill(key, (byte) 0); }
        assertFalse(records.isEmpty());
        int successful = 0;
        for (var credential : records) {
            var person = users.findById(credential.id()).orElseThrow();
            assertTrue(PasswordHashes.isSupported(person.getPassword()), "保存形式が不正です。");
            assertEquals(credential.after(),person.getPassword(),"試験バックアップのハッシュが一致しません。");
            assertTrue(PasswordHashes.encoder().matches(credential.before(), person.getPassword()), "移行後のパスワードが一致しません。");
            // 無効社員はハッシュ照合だけ。繰り返し試験で実アカウントをロックしない。
            if (!person.isActive()) continue;
            var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
            var loginPage = get(client, "/"); assertEquals(200, loginPage.statusCode());
            assertTrue(loginPage.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            String cookie = loginPage.headers().firstValue("Set-Cookie").orElse("");
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"), "会話Cookieの属性が不足しています。");
            String initial = csrf(loginPage.body());
            String body = "userName=" + encode(person.getUsername()) + "&password=" + encode(credential.before());
            assertEquals(403, post(client, "/users/login", body, null).statusCode());
            var login = post(client, "/users/login", body, initial);
            assertEquals(200, login.statusCode());
            assertEquals(person.isActive() ? "OK" : "NG", login.body(), "既存アカウントの認証結果が不正です。");
            if (!person.isActive()) continue;
            successful++;
            assertEquals("ADMIN".equals(person.getRole()) ? 200 : 403, get(client, "/api/dashboard").statusCode());
            // 認証・トークンの検証は重い勤怠集計を介さず、軽量な共通画面を使う。
            var page = get(client, "/"); assertEquals(200, page.statusCode());
            String current = csrf(page.body());
            assertEquals(403, post(client, "/logout", "", initial).statusCode(), "ログイン前のトークンが残っています。");
            assertEquals(403, post(client, "/logout", "", null).statusCode());
            get(client, "/logout"); // GETでsessionを失効させない。
            assertEquals("ADMIN".equals(person.getRole()) ? 200 : 403, get(client, "/api/dashboard").statusCode());
            assertEquals(200, post(client, "/logout", "", current).statusCode());
            assertEquals(401, get(client, "/api/dashboard").statusCode());
        }
        assertTrue(successful > 0, "有効な既存ユーザーの確認が必要です。");
        System.out.println("試験資格情報のHTTP認証・CSRF・POSTログアウト確認: " + records.size() + "件（秘密情報非表示）");
    }

    @Test void databaseConstraintRejectsPlaintextAndKeepsAllAccountsHashed() throws Exception {
        try (var connection = source.getConnection()) {
            com.example.demo.acceptance.IsolatedDatabase.require(connection);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,password FROM sys_user ORDER BY id")) {
                Long first = null;
                while (rows.next()) { if (first == null) first = rows.getLong(1); assertTrue(PasswordHashes.isSupported(rows.getString(2))); }
                assertNotNull(first);
                connection.setAutoCommit(false);
                try (var update = connection.prepareStatement("UPDATE sys_user SET password=? WHERE id=?")) {
                    update.setString(1, "RejectedPlain123!"); update.setLong(2, first);
                    var failure = assertThrows(java.sql.SQLException.class, update::executeUpdate);
                    assertEquals(3819, failure.getErrorCode(), "ハッシュ形式CHECKで拒否されていません。");
                } finally { connection.rollback(); }
            }
        }
    }
}
