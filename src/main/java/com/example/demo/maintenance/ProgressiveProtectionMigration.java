package com.example.demo.maintenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** 13の追加列だけを適用する。旧アプリでも使える加算型DDL。 */
public final class ProgressiveProtectionMigration {
    private static String setting(Properties p,String key){
        String value=p.getProperty(key);
        if(value!=null && value.startsWith("${") && value.endsWith("}")){
            String expression=value.substring(2,value.length()-1);int colon=expression.indexOf(':');
            value=System.getenv(colon<0?expression:expression.substring(0,colon));
            if(value==null && colon>=0)value=expression.substring(colon+1);
        }
        if(value==null || value.isBlank())throw new IllegalStateException("DB設定が不足しています。");return value;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("設定ファイル・13SQL・新規記録先が必要です。");
        Path sql=Path.of(args[1]),backup=Path.of(args[2]).toAbsolutePath().normalize();
        if(!sql.getFileName().toString().equals("13_progressive_protection.sql") || !backup.startsWith(Path.of("D:/oa-maintenance").toAbsolutePath()) || Files.exists(backup))
            throw new IllegalArgumentException("13SQLとDドライブの新規記録先を指定してください。");
        Properties p=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){p.load(reader);}
        String url=setting(p,"spring.datasource.url");
        if(!url.startsWith("jdbc:mysql://") || !url.contains(".aivencloud.com:") || !url.contains("/oa_system?")
                || !Pattern.compile("(?:[?&])sslMode=(REQUIRED|VERIFY_CA|VERIFY_IDENTITY)(?:&|$)").matcher(url).find())
            throw new IllegalArgumentException("Aiven oa_systemのTLS接続だけを使用します。");
        String text=Files.readString(sql,StandardCharsets.UTF_8);
        var parser=Pattern.compile("ALTER TABLE (security_login_attempt|security_console_operator) ADD COLUMN ([a-z_]+) (INT NOT NULL DEFAULT 0|BIGINT NOT NULL DEFAULT 0|DATETIME\\(6\\) NULL|VARCHAR\\(32\\) NULL)");
        var allowed=Set.of("security_login_attempt.failure_stage","security_login_attempt.protection_version","security_login_attempt.protected_at","security_login_attempt.protection_reason",
                "security_console_operator.failure_window_start","security_console_operator.failure_stage","security_console_operator.login_protected","security_console_operator.protected_at","security_console_operator.protection_reason");
        List<String[]> statements=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String part:text.replaceAll("(?m)^\\s*--.*$","").split(";")){
            if(part.isBlank())continue;var match=parser.matcher(part.trim());
            if(!match.matches() || !allowed.contains(match.group(1)+"."+match.group(2)) || !seen.add(match.group(1)+"."+match.group(2)))
                throw new IllegalArgumentException("許可されていない13DDLです。");
            statements.add(new String[]{match.group(1),match.group(2),part.trim()});
        }
        if(!seen.equals(allowed))throw new IllegalArgumentException("13DDLの列が不足しています。");
        try(Connection c=DriverManager.getConnection(url,setting(p,"spring.datasource.username"),setting(p,"spring.datasource.password"))){
            if(!"oa_system".equals(c.getCatalog()))throw new IllegalStateException("DBが一致しません。");
            try(var s=c.createStatement();var r=s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){if(!r.next()||r.getString(2).isBlank())throw new IllegalStateException("TLSが必要です。");}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('oa_system_security_schema',10)")){if(!r.next()||r.getInt(1)!=1)throw new IllegalStateException("他の移行を確認してください。");}
            try{
                Files.createDirectories(backup);StringBuilder before=new StringBuilder(),receipt=new StringBuilder("migration=13\nexisting-credentials-and-business-data=not-modified\n");
                for(String table:List.of("security_login_attempt","security_console_operator"))try(var s=c.createStatement();var r=s.executeQuery("SHOW CREATE TABLE "+table)){r.next();before.append(r.getString(2)).append(";\n\n");}
                Files.writeString(backup.resolve("schema-before.sql"),before,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                Files.writeString(backup.resolve("migration.sql"),text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                for(var entry:statements){
                    try(var q=c.prepareStatement("SELECT column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?")){
                        q.setString(1,entry[0]);q.setString(2,entry[1]);
                        try(var r=q.executeQuery()){
                            if(r.next()){
                                String type=r.getString(1).toLowerCase(Locale.ROOT),expected=entry[2].substring(entry[2].indexOf(entry[1])+entry[1].length()).trim().split(" ")[0].toLowerCase(Locale.ROOT);
                                if(!type.equals(expected))throw new IllegalStateException("既存列の型が13仕様と異なります。");
                                receipt.append(entry[0]).append('.').append(entry[1]).append("=existing\n");continue;
                            }
                        }
                    }
                    try(var s=c.createStatement()){s.execute(entry[2]);}
                    receipt.append(entry[0]).append('.').append(entry[1]).append("=added\n");
                }
                Files.writeString(backup.resolve("receipt.txt"),receipt,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                System.out.println("13の追加9列を確認しました。既存の社員・資格情報・有効状態は変更していません。");
            }finally{try(var s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('oa_system_security_schema')");}}
        }catch(SQLException failure){System.err.println("13移行失敗 SQLState="+failure.getSQLState()+" code="+failure.getErrorCode());System.exit(1);}
    }
}
