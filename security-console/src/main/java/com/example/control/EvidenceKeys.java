package com.example.control;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/** 出力は初期化スクリプトがメモリで受け取り、端末へ表示しない。 */
public final class EvidenceKeys {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Dドライブの公開鍵ファイルを指定してください。");
        Path path=Path.of(args[0]).toAbsolutePath().normalize();
        if(!path.startsWith(Path.of("D:/oa-maintenance"))||Files.exists(path))throw new IllegalArgumentException("公開鍵は上書きしません。");
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var pair=generator.generateKeyPair();
        String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n";
        Files.createDirectories(path.getParent());Files.writeString(path,pem,StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW);
        System.out.print(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
    }
}
