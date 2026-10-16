package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import com.example.audit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** 合成ログイン名と試験専用の一時鍵。実運用の復号鍵は読み込まない。 */
@EnabledIfEnvironmentVariable(named="OA_ACCEPTANCE_TEST",matches="true")
class IsolatedAccessEvidenceIntegrationTest extends com.example.demo.acceptance.IsolatedIntegrationSupport {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Test void actualRejectedLoginStoresExactPeerAndWhitelistedFieldsAsCiphertext()throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        String base="http://127.0.0.1:"+port;
        var page=client.send(HttpRequest.newBuilder(URI.create(base+"/")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,page.statusCode());
        var token=Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"").matcher(page.body());assertTrue(token.find());
        String login="evidence_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        var request=HttpRequest.newBuilder(URI.create(base+"/users/login?testSecret=not-collected")).timeout(Duration.ofSeconds(20))
            .header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",token.group(1)).header("User-Agent","EvidenceIntegration/1.0 (Windows NT 10.0)")
            .header("Accept-Language","ja-JP").header("X-Forwarded-For","203.0.113.100")
            .POST(HttpRequest.BodyPublishers.ofString("userName="+login+"&password=synthetic-not-logged&otp=654321")).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals("NG",response.body());
        String id=response.headers().firstValue("X-Request-ID").orElseThrow();var row=jdbc.queryForMap("SELECT encrypted_detail FROM security_access_evidence WHERE correlation_id=?",id);
        String encrypted=row.get("encrypted_detail").toString();assertFalse(encrypted.contains("127.0.0.1"));assertFalse(encrypted.contains(login));
        var clear=AccessEnvelope.open(evidencePrivateKey(),id,encrypted);
        assertEquals("127.0.0.1",clear.get("ip"));assertEquals(login,clear.get("attemptedLogin"));assertEquals("/users/login",clear.get("path"));assertEquals("ja-JP",clear.get("language"));
        for(String secret:List.of("synthetic-not-logged","654321","not-collected","203.0.113.100"))assertFalse(clear.toString().contains(secret));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM security_source_profile WHERE source_key=?",Integer.class,com.example.demo.security.SecurityRequestContext.hash("127.0.0.1")));
    }
}
