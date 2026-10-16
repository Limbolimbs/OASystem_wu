package com.example.control;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 個人TOTPはハッシュでなく認証付き暗号化。本機DPAPI内の専用鍵で検証時だけ復号する。 */
@Component
public class MemberSecrets {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public MemberSecrets(@Value("${control.member-key:}") String encoded) {
        if(encoded==null || encoded.isBlank())throw new IllegalStateException("個人認証の鍵が未設定です。enable-members.ps1を実行してください。");
        key=Base64.getDecoder().decode(encoded);
        if(key.length!=32)throw new IllegalStateException("個人認証の鍵を確認してください。");
    }
    public String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public String totp(){byte[] bytes=new byte[20];random.nextBytes(bytes);return Totp.encode(bytes);}
    public String encrypt(String owner,String id,String secret){
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD((owner+":"+id).getBytes(StandardCharsets.US_ASCII));
            return "1."+Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII)));
        }catch(java.security.GeneralSecurityException failure){throw new IllegalStateException("個人認証を保護できません。");}
    }
    public String decrypt(String owner,String id,String value){
        byte[] clear=null;
        try{
            String[] parts=value.split("\\.");if(parts.length!=3 || !parts[0].equals("1"))throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getUrlDecoder().decode(parts[1])));cipher.updateAAD((owner+":"+id).getBytes(StandardCharsets.US_ASCII));
            clear=cipher.doFinal(Base64.getUrlDecoder().decode(parts[2]));String result=new String(clear,StandardCharsets.US_ASCII);if(!result.matches("[A-Z2-7]{32}"))throw new IllegalArgumentException();return result;
        }catch(Exception failure){throw new IllegalStateException("個人認証の保存情報を確認できません。");}
        finally{if(clear!=null)Arrays.fill(clear,(byte)0);}
    }
    public static void password(String password){
        if(password==null || password.length()<12 || password.length()>128 || password.chars().anyMatch(c->c<33 || c>126))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"パスワードは空白を含まない半角12～128文字で入力してください。");
        int classes=0;for(String pattern:List.of(".*[a-z].*",".*[A-Z].*",".*[0-9].*",".*[^a-zA-Z0-9].*"))if(password.matches(pattern))classes++;
        if(classes<3)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"大文字・小文字・数字・記号のうち3種類以上を含めてください。");
    }
}
