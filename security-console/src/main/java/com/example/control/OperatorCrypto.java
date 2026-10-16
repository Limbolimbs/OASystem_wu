package com.example.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.*;

public final class OperatorCrypto {
    private OperatorCrypto() {}
    public static PasswordEncoder encoder() { return new DelegatingPasswordEncoder("argon2", Map.of("argon2", new Argon2PasswordEncoder(16,32,1,19456,2))); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("識別子を生成できません。"); }
    }
}
