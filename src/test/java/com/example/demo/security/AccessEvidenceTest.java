package com.example.demo.security;

import com.example.audit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class AccessEvidenceTest {
    static KeyPair pair;
    @BeforeAll static void key()throws Exception{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);pair=generator.generateKeyPair();}
    String publicPem(){return "-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----";}
    @Test void hybridEncryptionPreservesFullIpv6AndAuthenticatesRequestAssociation()throws Exception{
        String id=UUID.randomUUID().toString();var fields=Map.of("ip","2001:db8:1234:5678:abcd:ef01:2345:6789","userAgent","<img onerror=alert(1)>");
        String first=AccessEnvelope.seal(pair.getPublic(),id,fields),second=AccessEnvelope.seal(pair.getPublic(),id,fields);
        assertNotEquals(first,second);assertFalse(first.contains("2001:db8"));assertEquals(fields,AccessEnvelope.open(pair.getPrivate(),id,first));
        assertThrows(GeneralSecurityException.class,()->AccessEnvelope.open(pair.getPrivate(),UUID.randomUUID().toString(),first));
        assertThrows(GeneralSecurityException.class,()->AccessEnvelope.open(pair.getPrivate(),id,first.substring(0,first.length()-5)+"abcde"));
    }
    @Test void fieldsDoNotTrustForwardedAddressOrCollectSecretsAndQuery() {
        var request=new MockHttpServletRequest("POST","/users/login?secret=do-not-store");request.setRemoteAddr("2001:db8::5");request.setRemotePort(12345);
        request.addHeader("X-Forwarded-For","8.8.8.8");request.addHeader("Forwarded","for=8.8.4.4");request.addHeader("Cookie","SID=secret-cookie");request.addHeader("Authorization","Bearer secret-token");
        request.addHeader("User-Agent","Desktop\r\nforged-log");request.addParameter("userName","tanaka");request.addParameter("password","never-log-password");request.addParameter("otp","654321");
        request.setQueryString("secret=do-not-store");var fields=AccessFields.capture(request);
        assertEquals("2001:db8::5",fields.get("ip"));assertEquals("/users/login",fields.get("path"));assertEquals("tanaka",fields.get("attemptedLogin"));
        String values=fields.toString();for(String forbidden:List.of("never-log-password","654321","secret-cookie","secret-token","do-not-store","8.8.8.8","\r","\n"))assertFalse(values.contains(forbidden));
    }
    @Test void literalIpValidationDoesNotAcceptHostsOrUrls() {
        for(String bad:List.of("example.com","http://127.0.0.1/","127.1","999.1.1.1","1.2.3.4:80"))assertNull(AccessFields.literal(bad));
        assertNotNull(AccessFields.literal("::1"));assertNotNull(AccessFields.literal("203.0.113.1"));
        assertNotNull(AccessFields.literal("fe80::1234%eth0"));
        var request=new MockHttpServletRequest("POST","/users/login;jsessionid=not-stored");request.setRemoteAddr("::1");assertEquals("/users/login",AccessFields.capture(request).get("path"));
    }
    @Test void auxiliaryStorageFailureIsPropagatedToAuditTransaction() {
        var jdbc=mock(JdbcTemplate.class);
        var loader=new DefaultResourceLoader(){@Override public Resource getResource(String location){return new ByteArrayResource(publicPem().getBytes(java.nio.charset.StandardCharsets.US_ASCII));}};
        var writer=new AccessEvidenceWriter(jdbc,java.time.Clock.systemUTC(),loader,"test-public-key");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic")).when(jdbc).update(anyString(),any(Object[].class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->writer.record(UUID.randomUUID().toString(),SecurityRequestContext.hash("127.0.0.1"),Map.of("ip","127.0.0.1")));
    }
}
