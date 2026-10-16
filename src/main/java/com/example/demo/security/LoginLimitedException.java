package com.example.demo.security;

public class LoginLimitedException extends org.springframework.security.core.AuthenticationException {
    private final long retryAfter;
    public LoginLimitedException(long retryAfter) { super("ログイン試行が制限されています。"); this.retryAfter = retryAfter; }
    public long retryAfter() { return retryAfter; }
}
