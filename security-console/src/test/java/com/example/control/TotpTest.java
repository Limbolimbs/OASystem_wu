package com.example.control;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
class TotpTest {
 @Test void matchesRfc6238Sha1Vectors(){
  String secret=Totp.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
  long[] times={59L,1111111109L,1111111111L,1234567890L,2000000000L,20000000000L};
  String[] expected={"94287082","07081804","14050471","89005924","69279037","65353130"};
  for(int n=0;n<times.length;n++)assertEquals(expected[n],Totp.code(secret,times[n]/30,8));
  assertEquals(1,Totp.matchingStep(secret,"287082",Instant.ofEpochSecond(59)));assertEquals(-1,Totp.matchingStep(secret,"bad",Instant.ofEpochSecond(59)));
 }
 @Test void passwordsUseSaltedArgon2idOnly(){var encoder=OperatorCrypto.encoder();String value="GeneratedExample123!";String hash=encoder.encode(value);assertTrue(hash.startsWith("{argon2}$argon2id$"));assertTrue(encoder.matches(value,hash));assertNotEquals(hash,encoder.encode(value));assertFalse(encoder.matches("bad",hash));}
}
