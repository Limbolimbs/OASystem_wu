package com.example.control;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
class MemberSecretsTest {
    @Test void independentKeysAuthenticatedEncryptionAndLiteralQrRoundTrip()throws Exception{
        var secrets=new MemberSecrets(Base64.getEncoder().encodeToString(new byte[32]));String first=secrets.totp(),second=secrets.totp();assertNotEquals(first,second);assertEquals(32,first.length());
        String owner=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),encrypted=secrets.encrypt(owner,id,first);assertFalse(encrypted.contains(first));assertEquals(first,secrets.decrypt(owner,id,encrypted));assertNotEquals(encrypted,secrets.encrypt(owner,id,first));assertThrows(IllegalStateException.class,()->secrets.decrypt(owner,UUID.randomUUID().toString(),encrypted));
        String image=EnrollmentQr.png("qa_member",first);var png=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(image.substring(image.indexOf(',')+1))));int[] pixels=png.getRGB(0,0,png.getWidth(),png.getHeight(),null,0,png.getWidth());var decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(png.getWidth(),png.getHeight(),pixels))));assertEquals(EnrollmentQr.uri("qa_member",first),decoded.getText());assertTrue(decoded.getText().endsWith("&digits=6&period=30"));
    }
    @Test void passwordRequiresTwelveAndThreeClasses(){for(String bad:List.of("Abc123!","abcdefghijkl","ABCDEFGHIJKL","123456789012","ABCDEFGHI123","Abc12345678 "))assertThrows(org.springframework.web.server.ResponseStatusException.class,()->MemberSecrets.password(bad));for(String good:List.of("Abcdefgh1234","ABCDEFG1234!","abcdefg1234!","Abcdefghijk!"))assertDoesNotThrow(()->MemberSecrets.password(good));}
}
