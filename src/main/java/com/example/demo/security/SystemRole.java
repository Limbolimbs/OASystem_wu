package com.example.demo.security;

public enum SystemRole {
    ADMIN,
    GENERAL_AFFAIRS,
    EMPLOYEE;

    public static SystemRole fromCode(String code) {
        if (code == null) {
            return EMPLOYEE;
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException exception) {
            return EMPLOYEE;
        }
    }
}
