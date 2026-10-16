package com.example.demo.security;

public class AuditUnavailableException extends RuntimeException {
    public AuditUnavailableException() { super("監査記録を保存できませんでした。操作は完了していません。"); }
}
