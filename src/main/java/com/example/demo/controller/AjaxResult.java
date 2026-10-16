package com.example.demo.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 非同期操作の共通応答。エンティティや認証情報をJSONに含めない。 */
public record AjaxResult(boolean success, String message, String redirectUrl) {
    public static ResponseEntity<AjaxResult> ok(String message) {
        return ResponseEntity.ok(new AjaxResult(true, message, null));
    }

    public static ResponseEntity<AjaxResult> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new AjaxResult(false, message,
                status == HttpStatus.UNAUTHORIZED ? "/" : null));
    }

    /** 任意のテンプレート名・セレクターを受け取らず、公開部分だけを返す。 */
    public static String view(String template, String fragment, String... allowed) {
        if (fragment == null) return template;
        for (String name : allowed) {
            if (name.equals(fragment)) return template + " :: " + name;
        }
        throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST, "表示対象を確認してください。");
    }
}
