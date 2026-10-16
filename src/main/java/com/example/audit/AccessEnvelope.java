package com.example.audit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** 原始IPを含む補助情報の封筒暗号。公開鍵側は復号できない。 */
public final class AccessEnvelope {
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    private static final OAEPParameterSpec OAEP=new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT);
    private AccessEnvelope() {}
    public static PublicKey publicKey(String pem) throws GeneralSecurityException {
        String encoded=pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        if(((java.security.interfaces.RSAPublicKey)key).getModulus().bitLength()<3072)throw new GeneralSecurityException("公開鍵の長さが不足しています。");
        return key;
    }
    public static PrivateKey privateKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
    public static String keyId(PublicKey key) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    }
    public static String keyId(PrivateKey key) throws GeneralSecurityException {
        var rsa=(java.security.interfaces.RSAPrivateCrtKey)key;
        return keyId(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent())));
    }
    private static byte[] encode(Map<String,String> fields) throws IOException {
        if(fields.size()>20)throw new IOException("補助情報が多すぎます。");
        var bytes=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(bytes)){
            output.writeInt(fields.size());
            for(var entry:fields.entrySet()){
                if(!entry.getKey().matches("[a-zA-Z_]{1,32}")||entry.getValue().length()>1200)throw new IOException("補助情報が長すぎます。");
                output.writeUTF(entry.getKey());output.writeUTF(entry.getValue());
            }
        }
        if(bytes.size()>8192)throw new IOException("補助情報が長すぎます。");return bytes.toByteArray();
    }
    public static String seal(PublicKey recipient,String correlation,Map<String,String> fields) throws GeneralSecurityException,IOException {
        if(!correlation.matches("[a-fA-F0-9-]{36}"))throw new GeneralSecurityException("相関IDの形式が不正です。");
        byte[] key=new byte[32],nonce=new byte[12];var random=new SecureRandom();random.nextBytes(key);random.nextBytes(nonce);
        try{
            var wrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");wrap.init(Cipher.ENCRYPT_MODE,recipient,OAEP);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(correlation.getBytes(StandardCharsets.US_ASCII));
            return "1."+keyId(recipient)+"."+ENCODER.encodeToString(wrap.doFinal(key))+"."+ENCODER.encodeToString(nonce)+"."+ENCODER.encodeToString(cipher.doFinal(encode(fields)));
        }finally{Arrays.fill(key,(byte)0);}
    }
    public static Map<String,String> open(PrivateKey recipient,String correlation,String envelope) throws GeneralSecurityException,IOException {
        if(envelope==null||envelope.length()>16000)throw new GeneralSecurityException("補助情報の形式が不正です。");
        String[] parts=envelope.split("\\.",-1);
        if(parts.length!=5||!parts[0].equals("1")||!parts[1].equals(keyId(recipient)))throw new GeneralSecurityException("復号鍵が一致しません。");
        byte[] key=null;
        try{
            var wrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");wrap.init(Cipher.DECRYPT_MODE,recipient,OAEP);key=wrap.doFinal(DECODER.decode(parts[2]));
            if(key.length!=32)throw new GeneralSecurityException("封筒鍵が不正です。");
            byte[] nonce=DECODER.decode(parts[3]);if(nonce.length!=12)throw new GeneralSecurityException("補助情報の形式が不正です。");
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(correlation.getBytes(StandardCharsets.US_ASCII));
            byte[] clear=cipher.doFinal(DECODER.decode(parts[4]));
            try(var input=new DataInputStream(new ByteArrayInputStream(clear))){
                int size=input.readInt();if(size<0||size>20)throw new IOException("補助情報が不正です。");
                var result=new LinkedHashMap<String,String>();
                for(int n=0;n<size;n++){String name=input.readUTF(),value=input.readUTF();if(!name.matches("[a-zA-Z_]{1,32}")||value.length()>1200||result.put(name,value)!=null)throw new IOException("補助情報が不正です。");}
                if(input.available()!=0)throw new IOException("補助情報が不正です。");return result;
            }finally{Arrays.fill(clear,(byte)0);}
        }catch(IllegalArgumentException failure){throw new GeneralSecurityException("補助情報の形式が不正です。");}
        finally{if(key!=null)Arrays.fill(key,(byte)0);}
    }
}
