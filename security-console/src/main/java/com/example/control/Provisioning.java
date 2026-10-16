package com.example.control;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** ローカル初期化専用。標準出力は呼出元のDPAPI処理へ渡し、端末へ表示しない。 */
public final class Provisioning {
    private static String setting(Properties p,String key) {
        String value=p.getProperty(key);
        if(value!=null&&value.startsWith("${")&&value.endsWith("}")){
            String s=value.substring(2,value.length()-1);int n=s.indexOf(':');String env=System.getenv(n<0?s:s.substring(0,n));value=env!=null?env:n<0?null:s.substring(n+1);
        }
        if(value==null||value.isBlank())throw new IllegalArgumentException("接続設定が不足しています。");return value;
    }
    private static String digest(Connection c)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        try(var s=c.createStatement();var r=s.executeQuery("SELECT id,username,password,role,status,created_at,updated_at,delete_flag,deleted_at FROM sys_user ORDER BY id")){
            while(r.next())for(int n=1;n<=9;n++){byte[] value=String.valueOf(r.getString(n)).getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());digest.update(value);}
        }return HexFormat.of().formatHex(digest.digest());
    }
    private static String random(int count){byte[] bytes=new byte[count];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String json(String value){StringBuilder s=new StringBuilder("\"");for(char c:value.toCharArray()){if(c=='"'||c=='\\')s.append('\\').append(c);else if(c<32)s.append(String.format("\\u%04x",(int)c));else s.append(c);}return s.append('"').toString();}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("接続設定と新しいDドライブ保守ディレクトリを指定してください。");
        Path backup=Path.of(args[1]).toAbsolutePath();if(!backup.startsWith(Path.of("D:/oa-maintenance"))||Files.exists(backup))throw new IllegalArgumentException("新しいDドライブ保守ディレクトリが必要です。");
        var properties=new Properties();try(var r=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){properties.load(r);}
        String url=setting(properties,"spring.datasource.url");
        if(!url.contains(".aivencloud.com:")||!url.contains("/oa_system?")||!java.util.regex.Pattern.compile("(?:\\?|&)sslMode=(?:REQUIRED|VERIFY_CA|VERIFY_IDENTITY)(?:&|$)").matcher(url).find())throw new IllegalArgumentException("対象DB・TLS接続を確認してください。");
        var values=new LinkedHashMap<String,String>();String databaseUser="control_"+UUID.randomUUID().toString().replace("-","").substring(0,16),databasePassword=random(32);
        try(var connection=DriverManager.getConnection(url,setting(properties,"spring.datasource.username"),setting(properties,"spring.datasource.password"))){
            if(!"oa_system".equals(connection.getCatalog()))throw new IllegalStateException("対象DBが違います。");
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){if(!rows.next()||rows.getString(2).isBlank())throw new IllegalStateException("TLSが必要です。");}
            String before=digest(connection);Files.createDirectory(backup);
            StringBuilder ddl=new StringBuilder();
            for(String table:List.of("sys_user","security_login_attempt","security_audit_log"))try(var s=connection.createStatement();var r=s.executeQuery("SHOW CREATE TABLE "+table)){r.next();ddl.append(r.getString(2)).append(";\n\n");}
            Files.writeString(backup.resolve("schema-before.sql"),ddl,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            String sql;try(var input=Provisioning.class.getResourceAsStream("/database/10_security_console.sql")){if(input==null)throw new IllegalStateException("管理SQLがありません。");sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
            Files.writeString(backup.resolve("migration.sql"),sql,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            for(String part:sql.replaceAll("(?m)^\\s*--.*$","").split(";")){if(!part.isBlank())try(var s=connection.createStatement()){s.execute(part);}}
            if(!before.equals(digest(connection)))throw new IllegalStateException("既存社員データの一致確認に失敗しました。");
            // 生成値は英数字・_・-のみ。SQLへユーザー入力を連結しない。
            String account="'"+databaseUser+"'@'%'";
            try(var s=connection.createStatement()){
                s.execute("CREATE USER "+account+" IDENTIFIED BY '"+databasePassword+"' REQUIRE SSL");
                s.execute("GRANT SELECT (id,username,role,status,delete_flag) ON oa_system.sys_user TO "+account);
                s.execute("GRANT SELECT ON oa_system.security_audit_log TO "+account);
                s.execute("GRANT SELECT,INSERT,UPDATE ON oa_system.security_login_attempt TO "+account);
                s.execute("GRANT SELECT,INSERT,UPDATE ON oa_system.security_console_operator TO "+account);
                s.execute("GRANT SELECT,INSERT ON oa_system.security_console_action TO "+account);
            }
            Files.writeString(backup.resolve("receipt.txt"),"migration=10\nexisting-employee-data=unchanged\nconsole-database-user="+databaseUser+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }catch(SQLException failure){System.err.println("管理初期化失敗 SQLState="+failure.getSQLState()+" code="+failure.getErrorCode());System.exit(1);return;}
        String password="Aa1!"+random(18);byte[] seed=new byte[20];new SecureRandom().nextBytes(seed);
        values.put("CONTROL_DB_URL",url);values.put("CONTROL_DB_USER",databaseUser);values.put("CONTROL_DB_PASSWORD",databasePassword);
        values.put("CONTROL_OPERATOR_ID",UUID.randomUUID().toString());values.put("CONTROL_LOGIN_ID","control_admin");
        values.put("CONTROL_PASSWORD_HASH",OperatorCrypto.encoder().encode(password));values.put("CONTROL_TOTP_SECRET",Totp.encode(seed));values.put("CONTROL_CERT_PASSWORD",random(24));
        values.put("enrollmentPassword",password);
        System.out.print(values.entrySet().stream().map(e->json(e.getKey())+":"+json(e.getValue())).collect(java.util.stream.Collectors.joining(",","{","}")));
    }
}
