package com.example.demo.controller;

import com.example.demo.security.AccountRules;
import com.example.demo.security.PortalPrincipal;
import com.example.demo.service.ProfileService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/my-page")
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    public String page(@AuthenticationPrincipal PortalPrincipal principal, Model model,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        model.addAttribute("profile", profiles.profile(principal));
        model.addAttribute("passwordRequirements", AccountRules.PASSWORD_REQUIREMENTS);
        model.addAttribute("passwordMinLength", AccountRules.PASSWORD_MIN_LENGTH);
        model.addAttribute("passwordMaxLength", AccountRules.PASSWORD_MAX_LENGTH);
        return "my-page";
    }

    @PostMapping("/password")
    public ResponseEntity<AjaxResult> password(@AuthenticationPrincipal PortalPrincipal principal,
            @RequestParam String currentPassword, @RequestParam String newPassword,
            @RequestParam String passwordConfirmation, HttpServletRequest request, HttpServletResponse response) {
        var result = profiles.changePassword(principal, currentPassword, newPassword,
                passwordConfirmation, request.getRemoteAddr());
        if (result.retryAfter() > 0 || result.protectedLogin()) {
            var builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore());
            if (result.retryAfter() > 0) builder.header("Retry-After", Long.toString(result.retryAfter()));
            return builder.body(new AjaxResult(false,
                    "本人確認の試行が制限されています。時間をおいて再度お試しいただくか、システム管理者へお問い合わせください。", null));
        }
        // コミット後に全会話が失効する。現在の認証とCookieも残さない。
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setHttpOnly(true); cookie.setSecure(request.isSecure()); cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AjaxResult(true,
                "パスワードを変更し、すべての端末のログインを終了しました。新しいパスワードでログインしてください。",
                request.getContextPath() + "/"));
    }
}
