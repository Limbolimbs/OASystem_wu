package com.example.demo.acceptance;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 旧クラウド試験の隔離実行基盤。本番設定を参照せず、接続先と識別行を検証する。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class IsolatedIntegrationSupport {
    private static final KeyPair EVIDENCE_KEYS = keys();
    @Autowired private DataSource isolatedSource;

    private static KeyPair keys() {
        try {
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }

    protected static PrivateKey evidencePrivateKey() { return EVIDENCE_KEYS.getPrivate(); }

    @DynamicPropertySource static void isolatedProperties(DynamicPropertyRegistry properties) throws Exception {
        try(var connection=IsolatedDatabase.connection();var query=connection.createStatement();
            var marker=query.executeQuery("SELECT purpose FROM qa_environment_marker WHERE id=1")) {
            if(!marker.next()||!"isolated business acceptance only".equals(marker.getString(1)))
                throw new IllegalStateException("起動前に専用テストDBの識別行を確認してください。");
        }
        Path directory=Path.of("D:/oa-maintenance/oa-skipped-tests-20261016");
        Files.createDirectories(directory);
        Path publicKey=directory.resolve("qa-evidence-public.pem");
        Files.writeString(publicKey,"-----BEGIN PUBLIC KEY-----\n"
            +Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(EVIDENCE_KEYS.getPublic().getEncoded())
            +"\n-----END PUBLIC KEY-----\n",StandardCharsets.US_ASCII);
        properties.add("spring.datasource.url",()->IsolatedDatabase.URL);
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->IsolatedDatabase.PASSWORD);
        properties.add("spring.datasource.hikari.maximum-pool-size",()->"6");
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone='+09:00'");
        properties.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        properties.add("spring.jpa.show-sql",()->"false");
        properties.add("spring.jpa.open-in-view",()->"false");
        properties.add("spring.sql.init.mode",()->"never");
        properties.add("server.address",()->"127.0.0.1");
        properties.add("oa.security.evidence-public-key",()->publicKey.toUri().toString());
        properties.add("oa.security.session-node",()->"isolated-skipped-followup");
    }

    @BeforeEach final void resetIsolatedData() throws Exception { IsolatedFixtures.reset(isolatedSource); }
    @AfterEach final void cleanIsolatedData() throws Exception { IsolatedFixtures.reset(isolatedSource); }
}
