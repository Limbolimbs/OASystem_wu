package com.example.control;

import com.example.audit.AccessEnvelope;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** DPAPI保守ストアを利用する本人の対話端末からだけ実施する。資格情報は変更しない。 */
public final class OwnerProtectionRecovery {
    private static String env(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("本機の保守設定が必要です。");return value;}
    public static void main(String[] args)throws Exception{
        var terminal=System.console();if(terminal==null)throw new IllegalStateException("本人の対話端末から実行してください。");
        String owner=UUID.fromString(env("CONTROL_OPERATOR_ID")).toString();
        terminal.printf("最高管理者の認証保護だけを解除します。パスワード・認証器・権限は変更しません。%n");
        String reason=ConsoleMembers.reason(terminal.readLine("本人確認・解除理由: "));
        char[] password=terminal.readPassword("現在の管理パスワード: "),code=terminal.readPassword("認証器の新しい6桁コード: ");
        if(password==null||code==null)throw new IllegalArgumentException("認証入力が必要です。");
        try{
            if(!"解除".equals(terminal.readLine("実行する場合は「解除」と入力: "))){terminal.printf("操作を中止しました。%n");return;}
            String url=env("CONTROL_DB_URL");if(!url.startsWith("jdbc:mysql://")||!url.contains("/oa_system?")||!url.matches(".*[?&]sslMode=(REQUIRED|VERIFY_CA|VERIFY_IDENTITY)(&.*)?$"))throw new IllegalStateException("保守DBのTLS設定を確認してください。");
            Path publicFile=Path.of("D:/oa-maintenance/control-public/evidence-public.pem");
            var key=AccessEnvelope.publicKey(Files.readString(publicFile,StandardCharsets.US_ASCII));
            try(Connection c=DriverManager.getConnection(url,env("CONTROL_DB_USER"),env("CONTROL_DB_PASSWORD"))){
                try(var zone=c.createStatement()){zone.execute("SET time_zone = '+09:00'");}
                c.setAutoCommit(false);
                try{
                    long last;boolean protectedLogin;
                    try(var s=c.prepareStatement("SELECT last_totp_step,login_protected,failed_attempts,blocked_until FROM security_console_operator WHERE id=? AND owner_id=? AND role='OWNER' AND active=1 AND delete_flag=0 AND enrollment_state='ACTIVE' FOR UPDATE")){
                        s.setString(1,owner);s.setString(2,owner);try(var r=s.executeQuery()){if(!r.next())throw new IllegalStateException("最高管理者の登録を確認してください。");last=r.getLong(1);protectedLogin=r.getInt(2)==1||r.getInt(3)>0||r.getTimestamp(4)!=null;}
                    }
                    long step=Totp.matchingStep(env("CONTROL_TOTP_SECRET"),new String(code),Instant.now());
                    boolean matched=OperatorCrypto.encoder().matches(java.nio.CharBuffer.wrap(password),env("CONTROL_PASSWORD_HASH"))&&step>last;
                    String correlation=UUID.randomUUID().toString(),source=OperatorCrypto.hash("127.0.0.1");
                    String sealed=AccessEnvelope.seal(key,correlation,Map.of("ip","127.0.0.1","method","LOCAL","path","/local/owner-protection-release","userAgent","Local authenticated maintenance","language","ja"));
                    try(var s=c.prepareStatement("INSERT INTO security_access_evidence(correlation_id,source_key,occurred_at,producer,key_id,encrypted_detail) VALUES(?,?,CURRENT_TIMESTAMP(6),'CONTROL',?,?)")){
                        s.setString(1,correlation);s.setString(2,source);s.setString(3,AccessEnvelope.keyId(key));s.setString(4,sealed);s.executeUpdate();
                    }
                    try(var s=c.prepareStatement("INSERT INTO security_console_action(operator_id,operation_code,target_type,target_key,result,reason,correlation_id,source_key) VALUES(?,?,'OPERATOR',?,?,?,?,?)")){
                        s.setString(1,owner);s.setString(2,matched?"OWNER_PROTECTION_RELEASE":"OWNER_RECOVERY_REJECTED");s.setString(3,OperatorCrypto.hash(owner));s.setString(4,matched?"SUCCESS":"REJECTED");s.setString(5,reason);s.setString(6,correlation);s.setString(7,source);s.executeUpdate();
                    }
                    if(!matched){c.commit();terminal.printf("認証を確認できません。保護は解除していません。%n");return;}
                    if(!protectedLogin){c.rollback();terminal.printf("解除対象の認証制限はありません。%n");return;}
                    try(var s=c.prepareStatement("UPDATE security_console_operator SET failed_attempts=0,failure_window_start=CURRENT_TIMESTAMP(6),failure_stage=0,blocked_until=NULL,login_protected=0,protected_at=NULL,protection_reason=NULL,otp_failures=0,otp_blocked_until=NULL,last_totp_step=?,auth_version=auth_version+1 WHERE id=? AND owner_id=?")){
                        s.setLong(1,step);s.setString(2,owner);s.setString(3,owner);if(s.executeUpdate()!=1)throw new IllegalStateException("解除対象が一致しません。");
                    }
                    c.commit();terminal.printf("認証制限を解除しました。新しい認証器コードで再ログインしてください。旧会話は復活しません。%n");
                }catch(Exception failure){c.rollback();throw failure;}
            }catch(SQLException failure){System.err.println("本機解除の保存障害 SQLState="+failure.getSQLState()+" code="+failure.getErrorCode());System.exit(1);}
        }finally{Arrays.fill(password,'\0');Arrays.fill(code,'\0');}
    }
}
