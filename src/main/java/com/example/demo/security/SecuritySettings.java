package com.example.demo.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("oa.security")
public class SecuritySettings {
    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration absoluteTimeout = Duration.ofHours(8);
    private int failureThreshold = 5;
    private Duration lockDuration = Duration.ofMinutes(15);
    private Duration failureObservationWindow = Duration.ofHours(24);
    private int secondFailureThreshold = 10;
    private Duration secondLockDuration = Duration.ofMinutes(60);
    private int protectionThreshold = 15;
    private int sourceLimit = 60;
    private Duration sourceWindow = Duration.ofMinutes(1);
    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration value) { idleTimeout = positive(value); }
    public Duration getAbsoluteTimeout() { return absoluteTimeout; }
    public void setAbsoluteTimeout(Duration value) { absoluteTimeout = positive(value); }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int value) { failureThreshold = positive(value); }
    public Duration getLockDuration() { return lockDuration; }
    public void setLockDuration(Duration value) { lockDuration = positive(value); }
    public Duration getFailureObservationWindow() { return failureObservationWindow; }
    public void setFailureObservationWindow(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("失敗観測期間は0より大きく30日以内です。");
        failureObservationWindow = value;
    }
    public int getSecondFailureThreshold() { return secondFailureThreshold; }
    public void setSecondFailureThreshold(int value) { secondFailureThreshold = positive(value); }
    public Duration getSecondLockDuration() { return secondLockDuration; }
    public void setSecondLockDuration(Duration value) { secondLockDuration = positive(value); }
    public int getProtectionThreshold() { return protectionThreshold; }
    public void setProtectionThreshold(int value) { protectionThreshold = positive(value); }
    public com.example.security.ProgressiveLoginPolicy loginPolicy() {
        return new com.example.security.ProgressiveLoginPolicy(failureObservationWindow, failureThreshold, lockDuration,
                secondFailureThreshold, secondLockDuration, protectionThreshold);
    }
    @jakarta.annotation.PostConstruct public void validate() { loginPolicy(); }
    public int getSourceLimit() { return sourceLimit; }
    public void setSourceLimit(int value) { sourceLimit = positive(value); }
    public Duration getSourceWindow() { return sourceWindow; }
    public void setSourceWindow(Duration value) { sourceWindow = positive(value); }
    private static Duration positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("セキュリティ期限は0より大きく1日以内で設定してください。");
        return value;
    }
    private static int positive(int value) {
        if (value < 1 || value > 10000) throw new IllegalArgumentException("セキュリティ回数設定が不正です。");
        return value;
    }
}
