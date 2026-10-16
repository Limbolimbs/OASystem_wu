package com.example.control;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238。30秒・6桁、隣接する1区間を許容。採用区間はDBで再使用を拒否する。 */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Totp() {}
    public static byte[] decode(String value) {
        if (!value.matches("[A-Z2-7]+")) throw new IllegalArgumentException("認証器の登録情報が不正です。");
        var bytes = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
        for (char ch : value.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(ch); bits += 5;
            if (bits >= 8) { bits -= 8; bytes.write((buffer >> bits) & 255); }
        }
        return bytes.toByteArray();
    }
    public static String encode(byte[] value) {
        StringBuilder text = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte b : value) { buffer = (buffer << 8) | (b & 255); bits += 8; while (bits >= 5) { bits -= 5; text.append(ALPHABET.charAt((buffer >> bits) & 31)); } }
        if (bits > 0) text.append(ALPHABET.charAt((buffer << (5-bits)) & 31)); return text.toString();
    }
    public static String code(String secret, long step, int digits) {
        if (digits != 6 && digits != 8) throw new IllegalArgumentException("認証コードの桁数が不正です。");
        try {
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array()); int offset = hash[19] & 15;
            int binary = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
            return String.format(java.util.Locale.ROOT, "%0" + digits + "d", binary % (digits == 6 ? 1000000 : 100000000));
        } catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException("認証コードを検証できません。"); }
    }
    public static long matchingStep(String secret, String entered, Instant now) {
        if (entered == null || !entered.matches("[0-9]{6}")) return -1;
        long current = now.getEpochSecond() / 30;
        for (long step = current - 1; step <= current + 1; step++) {
            if (step >= 0 && MessageDigest.isEqual(code(secret,step,6).getBytes(StandardCharsets.US_ASCII), entered.getBytes(StandardCharsets.US_ASCII))) return step;
        }
        return -1;
    }
}
