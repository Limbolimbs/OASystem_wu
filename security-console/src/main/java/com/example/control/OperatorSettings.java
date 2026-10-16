package com.example.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.time.Duration;

@Component
@ConfigurationProperties("control")
public class OperatorSettings {
    private String operatorId, loginId, passwordHash, totpSecret;
    private Duration failureObservationWindow=Duration.ofHours(24),firstLock=Duration.ofMinutes(15),secondLock=Duration.ofMinutes(60);
    private int firstThreshold=5,secondThreshold=10,protectionThreshold=15;
    public Duration getFailureObservationWindow(){return failureObservationWindow;}
    public void setFailureObservationWindow(Duration value){failureObservationWindow=value;}
    public Duration getFirstLock(){return firstLock;}
    public void setFirstLock(Duration value){firstLock=value;}
    public Duration getSecondLock(){return secondLock;}
    public void setSecondLock(Duration value){secondLock=value;}
    public int getFirstThreshold(){return firstThreshold;}
    public void setFirstThreshold(int value){firstThreshold=value;}
    public int getSecondThreshold(){return secondThreshold;}
    public void setSecondThreshold(int value){secondThreshold=value;}
    public int getProtectionThreshold(){return protectionThreshold;}
    public void setProtectionThreshold(int value){protectionThreshold=value;}
    public com.example.security.ProgressiveLoginPolicy loginPolicy(){return new com.example.security.ProgressiveLoginPolicy(failureObservationWindow,firstThreshold,firstLock,secondThreshold,secondLock,protectionThreshold);}
    @jakarta.annotation.PostConstruct public void validate(){loginPolicy();}
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String value) { operatorId = UUID.fromString(value).toString(); }
    public String getLoginId() { return loginId; }
    public void setLoginId(String value) { if (!value.matches("[A-Za-z0-9_-]{4,50}")) throw new IllegalArgumentException("管理IDの形式が不正です。"); loginId = value; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String value) { if (value==null || !value.matches("\\{argon2}\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}")) throw new IllegalArgumentException("管理パスワードのハッシュが必要です。"); passwordHash = value; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String value) { if (!value.matches("[A-Z2-7]{32}")) throw new IllegalArgumentException("認証器の登録情報が不正です。"); totpSecret = value; }
}
