package com.example.demo.service;

import java.time.LocalDateTime;

import com.example.demo.entity.User;
import com.example.demo.security.AccountRules;
import com.example.demo.security.LoginProtectionService;
import com.example.demo.security.PortalPrincipal;
import com.example.demo.security.PortalSessionFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 本人専用の参照・変更。会社管理項目や任意の対象ユーザーIDを受け取らない。 */
@Service
public class ProfileService {
    public record Profile(String loginId, String name, String department, String roleLabel,
            LocalDateTime registeredAt, boolean admin, boolean sales, boolean approver) {}

    private final UserService users;
    private final DepartmentService departments;
    private final LoginProtectionService protection;

    public ProfileService(UserService users, DepartmentService departments, LoginProtectionService protection) {
        this.users = users;
        this.departments = departments;
        this.protection = protection;
    }

    private User current(PortalPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findById(principal.userId())
                .filter(User::isActive)
                .filter(user -> PortalSessionFilter.knownRole(user.getRole()))
                .filter(user -> user.getAuthenticationVersion() == principal.authenticationVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public Profile profile(PortalPrincipal principal) {
        User user = current(principal);
        boolean admin = "ADMIN".equals(user.getRole());
        String role = switch (user.getRole()) {
            case "ADMIN" -> "管理者";
            case "GENERAL_AFFAIRS" -> "総務担当";
            default -> "一般社員";
        };
        return new Profile(user.getUsername(), user.getRealName(), user.getDepartment(), role,
                user.getCreatedAt(), admin, admin || departments.isSalesDepartment(user.getDepartment()),
                admin || "GENERAL_AFFAIRS".equals(user.getRole()));
    }

    public LoginProtectionService.Outcome changePassword(PortalPrincipal principal, String currentPassword,
            String newPassword, String confirmation, String remoteAddress) {
        User user = current(principal);
        AccountRules.validateNewPassword(newPassword);
        AccountRules.validatePasswordConfirmation(newPassword, confirmation);
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("現在のパスワードを入力してください。");
        }
        // 失敗回数のTXをパスワード更新TXから分離し、更新拒否でも失敗記録を残す。
        LoginProtectionService.Outcome outcome;
        try {
            outcome = protection.verifyPassword(user, currentPassword, remoteAddress);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(ProfileService.class).error(
                    "本人再認証の保存障害 correlationId={}", com.example.demo.security.SecurityRequestContext.current().correlationId());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "認証の安全情報を確認できません。パスワードは変更していません。");
        }
        if (outcome.retryAfter() > 0 || outcome.protectedLogin()) return outcome;
        if (outcome.user() == null || !user.getId().equals(outcome.user().getId())) {
            throw new IllegalArgumentException("現在のパスワードを確認してください。");
        }
        // 排他取得後に現在のハッシュを再照合し、変更と監査を同じTXで確定する。
        users.changePassword(user.getId(), currentPassword, newPassword);
        return outcome;
    }
}
