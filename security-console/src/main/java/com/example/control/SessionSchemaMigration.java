package com.example.control;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** 14専用の追加DDL。既存資格情報・社員原本は更新しない。 */
public final class SessionSchemaMigration {
    public static void main(String[] args)throws Exception{
        if(args.length!=4||!args[3].matches("control_[a-f0-9]{16}"))throw new IllegalArgumentException("14SQL・新規Dドライブ保守先・限定DBユーザーを確認してください。");
        Path sql=Path.of(args[1]),backup=Path.of(args[2]).toAbsolutePath().normalize();
        if(!sql.getFileName().toString().equals("14_session_management.sql")||!backup.startsWith(Path.of("D:/oa-maintenance").toAbsolutePath())||Files.exists(backup))throw new IllegalArgumentException("14専用の新規保守先を指定してください。");
        Properties p=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){p.load(reader);}
        String url=MemberSchemaMigration.setting(p,"spring.datasource.url");
        if(!url.contains(".aivencloud.com:")||!url.contains("/oa_system?")||!url.matches(".*[?&]sslMode=(REQUIRED|VERIFY_CA|VERIFY_IDENTITY)(&.*)?$"))throw new IllegalStateException("oa_systemのTLS接続が必要です。");
        List<String> statements=new ArrayList<>();String text=Files.readString(sql,StandardCharsets.UTF_8);
        for(String part:text.replaceAll("(?m)^\\s*--.*$","").split(";")){
            String s=part.trim();if(s.isEmpty())continue;
            if(!s.matches("(?s)CREATE TABLE IF NOT EXISTS security_session_(registry|subject_guard) \\(.*|ALTER TABLE security_console_review ADD COLUMN session_(version|sequence) BIGINT NULL|ALTER TABLE security_console_review DROP CHECK ck_console_review_operation|ALTER TABLE security_console_review ADD CONSTRAINT ck_console_review_operation CHECK\\(operation_code IN\\('ACCOUNT_PROTECT','ACCOUNT_RELEASE','SOURCE_RESTRICT','SOURCE_RELEASE','SESSION_REVOKE','SESSION_REVOKE_ALL'\\)\\)"))throw new IllegalArgumentException("14以外のDDLは実行できません。");
            statements.add(s);
        }
        if(statements.size()!=6)throw new IllegalArgumentException("14のDDLが不足しています。");
        try(var c=DriverManager.getConnection(url,MemberSchemaMigration.setting(p,"spring.datasource.username"),MemberSchemaMigration.setting(p,"spring.datasource.password"))){
            if(!"oa_system".equals(c.getCatalog()))throw new IllegalStateException("DBが一致しません。");
            try(var s=c.createStatement();var r=s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){if(!r.next()||r.getString(2).isBlank())throw new IllegalStateException("TLSが必要です。");}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT GET_LOCK('oa_system_security_schema',10)")){if(!r.next()||r.getInt(1)!=1)throw new IllegalStateException("他の移行を確認してください。");}
            try{
                Files.createDirectories(backup);StringBuilder ddl=new StringBuilder();
                for(String table:List.of("sys_user","security_console_operator","security_console_review"))try(var s=c.createStatement();var r=s.executeQuery("SHOW CREATE TABLE "+table)){r.next();ddl.append(r.getString(2)).append(";\n\n");}
                Files.writeString(backup.resolve("schema-before.sql"),ddl,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                Files.writeString(backup.resolve("migration.sql"),text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                String account="'"+args[3]+"'@'%'";
                try(var s=c.createStatement();var r=s.executeQuery("SHOW GRANTS FOR "+account)){StringBuilder grants=new StringBuilder();while(r.next())grants.append(r.getString(1)).append('\n');Files.writeString(backup.resolve("grants-before.sql"),grants,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
                boolean newCheck=false,checkExists=false;
                try(var s=c.createStatement();var r=s.executeQuery("SELECT CHECK_CLAUSE FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_console_review_operation'")){if(r.next()){checkExists=true;newCheck=r.getString(1).contains("SESSION_REVOKE_ALL");}}
                for(String statement:statements){
                    if(statement.contains("ADD COLUMN")){String column=statement.contains("session_version")?"session_version":"session_sequence";try(var s=c.prepareStatement("SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='security_console_review' AND column_name=?")){s.setString(1,column);try(var r=s.executeQuery()){if(r.next()){if(!r.getString(1).equalsIgnoreCase("bigint"))throw new IllegalStateException("既存の14列の型を確認してください。");continue;}}}}
                    if(statement.contains("DROP CHECK")&&(!checkExists||newCheck)||statement.contains("ADD CONSTRAINT")&&newCheck)continue;
                    try(var s=c.createStatement()){s.execute(statement);}
                }
                try(var s=c.createStatement()){
                    s.execute("GRANT SELECT,INSERT,UPDATE ON oa_system.security_session_registry TO "+account);
                    s.execute("GRANT SELECT,INSERT,UPDATE ON oa_system.security_session_subject_guard TO "+account);
                    s.execute("GRANT SELECT (authentication_version,delete_flag) ON oa_system.sys_user TO "+account);
                }
                Files.writeString(backup.resolve("receipt.txt"),"migration=14\ncredential-and-employee-data=not-updated\nnew-session-tables=2\nreview-columns=2\nlimited-user-session-rights=select-insert-update\nsys-user-extra-select=authentication_version-delete_flag-only\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                System.out.println("14の会話管理構造を適用しました。既存資格情報・社員原本の変更なし。");
            }finally{try(var s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('oa_system_security_schema')");}}
        }catch(SQLException failure){System.err.println("14の移行失敗 SQLState="+failure.getSQLState()+" code="+failure.getErrorCode());System.exit(1);}
    }
}
