package com.example.control;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.security.*;
import java.util.*;
/** 12専用の追加入力。既存社員及び既存管理認証列を照合し、DDL削除は許可しない。 */
public final class MemberSchemaMigration {
    static String setting(Properties p,String key){String value=p.getProperty(key);if(value!=null && value.startsWith("${") && value.endsWith("}")){String token=value.substring(2,value.length()-1);int split=token.indexOf(':');String env=System.getenv(split<0?token:token.substring(0,split));value=env!=null?env:split<0?null:token.substring(split+1);}if(value==null||value.isBlank())throw new IllegalStateException("接続設定が不足しています。");return value;}
    static String digest(Connection c)throws Exception{
        var hash=MessageDigest.getInstance("SHA-256");
        for(String query:List.of("SELECT * FROM sys_user ORDER BY id","SELECT id,login_id,failed_attempts,blocked_until,last_totp_step,otp_failures,otp_blocked_until,active,created_at,updated_at,delete_flag,deleted_at FROM security_console_operator ORDER BY id"))try(var s=c.createStatement();var r=s.executeQuery(query)){while(r.next())for(int col=1;col<=r.getMetaData().getColumnCount();col++){byte[] bytes=Objects.toString(r.getString(col),"<NULL>").getBytes(StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}}
        return HexFormat.of().formatHex(hash.digest());
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4 || !args[3].matches("control_[a-f0-9]{16}"))throw new IllegalArgumentException("12の接続設定・SQL・新規Dドライブ保守先・限定DBユーザーが必要です。");
        Path sqlPath=Path.of(args[1]),backup=Path.of(args[2]).toAbsolutePath().normalize();if(!sqlPath.getFileName().toString().equals("12_console_members.sql") || !backup.startsWith(Path.of("D:/oa-maintenance")) || Files.exists(backup))throw new IllegalArgumentException("12専用の新規保守先を確認してください。");
        var p=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){p.load(reader);}String url=setting(p,"spring.datasource.url");if(!url.contains(".aivencloud.com:") || !url.contains("/oa_system?") || !java.util.regex.Pattern.compile("(?:\\?|&)sslMode=(?:REQUIRED|VERIFY_CA|VERIFY_IDENTITY)(?:&|$)").matcher(url).find())throw new IllegalArgumentException("対象DBとTLSを確認してください。");
        try(var c=DriverManager.getConnection(url,setting(p,"spring.datasource.username"),setting(p,"spring.datasource.password"))){
            if(!"oa_system".equals(c.getCatalog()))throw new IllegalStateException("DB名が異なります。");try(var s=c.createStatement();var r=s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){if(!r.next() || r.getString(2).isBlank())throw new IllegalStateException("TLS接続が必要です。");}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('oa_system_security_schema',10)")){if(!r.next()||r.getInt(1)!=1)throw new IllegalStateException("他の移行が実行中です。");}
            try{
                Files.createDirectory(backup);String before=digest(c);StringBuilder ddl=new StringBuilder();for(String table:List.of("sys_user","security_console_operator","security_console_action"))try(var s=c.createStatement();var r=s.executeQuery("SHOW CREATE TABLE "+table)){r.next();ddl.append(r.getString(2)).append(";\n\n");}Files.writeString(backup.resolve("schema-before.sql"),ddl,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                String sql=Files.readString(sqlPath,StandardCharsets.UTF_8);Files.writeString(backup.resolve("migration.sql"),sql,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                for(String part:sql.replaceAll("(?m)^\\s*--.*$","").split(";")){String statement=part.trim();if(statement.isEmpty())continue;if(!statement.matches("(?is)^(USE oa_system|SET time_zone = '\\+09:00'|ALTER TABLE security_console_(operator|entry_limit)\\s+ADD COLUMN .+|CREATE TABLE IF NOT EXISTS security_console_(entry_limit|review) .+)$") || statement.matches("(?is).*\\b(DROP|DELETE|TRUNCATE)\\b.*"))throw new IllegalStateException("12以外のSQLは実行できません。");
                    if(statement.startsWith("ALTER TABLE")){boolean operator=statement.startsWith("ALTER TABLE security_console_operator");String columnCheck=operator?"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='security_console_operator' AND column_name IN('owner_id','role','display_name','password_hash','totp_secret_enc','enrollment_state','invitation_hash','enrollment_until','auth_version')":"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='security_console_entry_limit' AND column_name IN('created_at','updated_at','delete_flag','deleted_at')";try(var s=c.createStatement();var r=s.executeQuery(columnCheck)){r.next();int count=r.getInt(1);if(count==(operator?9:4))continue;if(count!=0)throw new IllegalStateException("部分適用を検出しました。DDLを確認してください。");}}
                    try(var s=c.createStatement()){s.execute(statement);}
                }
                String account="'"+args[3]+"'@'%'";try(var s=c.createStatement()){s.execute("GRANT SELECT,INSERT,UPDATE ON oa_system.security_console_operator TO "+account);s.execute("GRANT SELECT,INSERT,UPDATE ON oa_system.security_console_entry_limit TO "+account);s.execute("GRANT SELECT,INSERT,UPDATE ON oa_system.security_console_review TO "+account);}
                if(!before.equals(digest(c)))throw new IllegalStateException("既存社員・管理認証状態の一致を確認できません。");Files.writeString(backup.resolve("receipt.txt"),"migration=12\nexisting-employee-and-operator-auth-columns=unchanged\nmail=none\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);System.out.println("12の個人認証と権限構造を適用しました。既存社員・管理資格情報の変更なし。");
            }finally{try(var s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('oa_system_security_schema')");}}
        }catch(SQLException failure){System.err.println("12移行失敗 SQLState="+failure.getSQLState()+" code="+failure.getErrorCode());System.exit(1);}
    }
}
