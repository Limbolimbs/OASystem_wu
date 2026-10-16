package com.example.control;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.security.*;
import java.util.*;

/** 11専用。既存の認証・社員レコードは変更しない。資格情報はファイルから取得する。 */
public final class EvidenceSchemaMigration {
    private static String setting(Properties p,String key){
        String value=p.getProperty(key);if(value!=null&&value.startsWith("${")&&value.endsWith("}")){String token=value.substring(2,value.length()-1);int split=token.indexOf(':');String environment=System.getenv(split<0?token:token.substring(0,split));value=environment!=null?environment:split<0?null:token.substring(split+1);}
        if(value==null||value.isBlank())throw new IllegalStateException("接続設定が不足しています。");return value;
    }
    private static String digest(Connection c)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        for(String table:List.of("sys_user","security_console_operator")){
            try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY id")){
                while(r.next())for(int column=1;column<=r.getMetaData().getColumnCount();column++){byte[] data=Objects.toString(r.getString(column),"<NULL>").getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());digest.update(data);}
            }
        }return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4||!args[3].matches("control_[a-f0-9]{16}"))throw new IllegalArgumentException("接続設定・11SQL・Dドライブ新規保守先・限定DBユーザーを指定してください。");
        Path sqlPath=Path.of(args[1]),backup=Path.of(args[2]).toAbsolutePath().normalize();
        if(!sqlPath.getFileName().toString().equals("11_access_evidence.sql")||!backup.startsWith(Path.of("D:/oa-maintenance"))||Files.exists(backup))throw new IllegalArgumentException("11専用の新規保守パスが必要です。");
        var properties=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){properties.load(r);}
        String url=setting(properties,"spring.datasource.url");if(!url.contains(".aivencloud.com:")||!url.contains("/oa_system?")||!java.util.regex.Pattern.compile("(?:\\?|&)sslMode=(?:REQUIRED|VERIFY_CA|VERIFY_IDENTITY)(?:&|$)").matcher(url).find())throw new IllegalArgumentException("接続先DB・TLSを確認してください。");
        try(var connection=DriverManager.getConnection(url,setting(properties,"spring.datasource.username"),setting(properties,"spring.datasource.password"))){
            if(!connection.getCatalog().equals("oa_system"))throw new IllegalStateException("DB名が異なります。");
            try(var s=connection.createStatement();var r=s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){if(!r.next()||r.getString(2).isBlank())throw new IllegalStateException("TLS接続が必要です。");}
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT GET_LOCK('oa_system_security_schema',10)")){if(!r.next()||r.getInt(1)!=1)throw new IllegalStateException("他の移行が実行中です。");}
            try{
                Files.createDirectory(backup);String before=digest(connection);StringBuilder ddl=new StringBuilder();
                for(String table:List.of("sys_user","security_login_attempt","security_audit_log","security_console_operator","security_console_action"))try(var s=connection.createStatement();var r=s.executeQuery("SHOW CREATE TABLE "+table)){r.next();ddl.append(r.getString(2)).append(";\n\n");}
                Files.writeString(backup.resolve("schema-before.sql"),ddl,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                String sql=Files.readString(sqlPath,StandardCharsets.UTF_8);Files.writeString(backup.resolve("migration.sql"),sql,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                for(String part:sql.replaceAll("(?m)^\\s*--.*$","").split(";")){String statement=part.trim();if(statement.isEmpty())continue;if(!statement.matches("(?is)^(USE oa_system|SET time_zone = '\\+09:00'|CREATE TABLE IF NOT EXISTS security_(access_evidence|source_profile) .*)$"))throw new IllegalStateException("11以外のSQLは実行できません。");try(var s=connection.createStatement()){s.execute(statement);}}
                String account="'"+args[3]+"'@'%'";
                try(var s=connection.createStatement()){s.execute("GRANT SELECT,INSERT ON oa_system.security_access_evidence TO "+account);s.execute("GRANT SELECT ON oa_system.security_source_profile TO "+account);}
                if(!before.equals(digest(connection)))throw new IllegalStateException("既存社員・管理認証状態の一致を確認できません。");
                Files.writeString(backup.resolve("receipt.txt"),"migration=11\nexisting-employee-and-operator-data=unchanged\nlimited-grants=read-source-profile,read-and-insert-evidence\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                System.out.println("11の追加表と限定参照権限を適用しました。既存社員・管理認証状態の変更なし。");
            }finally{try(var s=connection.createStatement()){s.execute("SELECT RELEASE_LOCK('oa_system_security_schema')");}}
        }catch(SQLException failure){System.err.println("11移行失敗 SQLState="+failure.getSQLState()+" code="+failure.getErrorCode());System.exit(1);}
    }
}
