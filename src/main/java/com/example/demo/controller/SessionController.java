package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;

@Controller
public class SessionController {
    /** 認証・CSRF・期限の共通検証後、ユーザー操作の受信を返す。時刻は受け取らない。 */
    @PostMapping("/session/activity")
    public ResponseEntity<AjaxResult> activity() { return AjaxResult.ok("操作を確認しました。"); }

    @GetMapping("/logout")
    public ResponseEntity<Void> rejectGetLogout() { return ResponseEntity.status(405).header("Allow", "POST").build(); }
}
