package com.example.control;
import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
/** QRは本機で生成。外部画像APIへ登録キーを送信しない。 */
public final class EnrollmentQr {
    private EnrollmentQr(){}
    static String uri(String id,String secret){return "otpauth://totp/"+URLEncoder.encode("Security Console:"+id,StandardCharsets.UTF_8).replace("+","%20")+"?secret="+secret+"&issuer=Security%20Console&algorithm=SHA1&digits=6&period=30";}
    public static String png(String id,String secret){try{
        var matrix=new QRCodeWriter().encode(uri(id,secret),BarcodeFormat.QR_CODE,300,300,Map.of(EncodeHintType.MARGIN,4));var image=new BufferedImage(300,300,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<300;y++)for(int x=0;x<300;x++)image.setRGB(x,y,matrix.get(x,y)?0:0xffffff);
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return "data:image/png;base64,"+Base64.getEncoder().encodeToString(output.toByteArray());
    }catch(Exception failure){throw new IllegalStateException("認証器のQRを生成できません。");}}
}
