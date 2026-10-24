package com.example.demo.controller;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.demo.entity.User;
import com.example.demo.security.AccountProtection;
import com.example.demo.security.LoginProtectionService;
import com.example.demo.security.PortalPrincipal;
import com.example.demo.security.PortalSessionFilter;
import com.example.demo.security.SecurityRequestContext;
import com.example.demo.security.SessionLifecycle;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class LoginController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    private final LoginProtectionService loginProtectionService;
    private final UserService userService;
    private final AccountProtection accountProtection;
    private final SessionLifecycle sessionLifecycle;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public LoginController(LoginProtectionService loginProtectionService, UserService userService,
            AccountProtection accountProtection, SessionLifecycle sessionLifecycle,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.loginProtectionService = loginProtectionService;
        this.userService = userService;
        this.accountProtection = accountProtection;
        this.sessionLifecycle = sessionLifecycle;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    /** AJAXのログイン要求を受け取り、認証成功後にSessionへ保存する。 */
    @PostMapping("/users/login")
    @ResponseBody
    public String login(@RequestParam(defaultValue = "") String userName,
            @RequestParam(defaultValue = "") String password,
            HttpServletRequest request, HttpServletResponse response) {
        response.setContentType("text/plain;charset=UTF-8");

        LoginProtectionService.Outcome outcome;
        try {
            // パスワード照合・失敗回数・認証監査は共通のサービスで行う。
            outcome = loginProtectionService.login(userName.trim(), password, request.getRemoteAddr());
        } catch (RuntimeException exception) {
            clearLogin(request);
            return authenticationUnavailable(response, exception);
        }

        if (outcome.retryAfter() > 0 || outcome.protectedLogin()) {
            clearLogin(request);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            if (outcome.retryAfter() > 0) {
                response.setHeader("Retry-After", Long.toString(outcome.retryAfter()));
            }
            return "{\"success\":false,\"code\":\"LOGIN_LIMITED\","
                    + "\"message\":\"ログイン試行が制限されています。時間をおいて再度お試しいただくか、システム管理者へお問い合わせください。\"}";
        }

        User authenticatedUser = outcome.user();
        if (authenticatedUser == null) {
            clearLogin(request);
            return "NG";
        }

        try {
            // 認証処理中の無効化・権限変更も、Sessionの発行前に再確認する。
            Optional<User> foundUser = userService.findById(authenticatedUser.getId());
            if (foundUser.isEmpty()) {
                clearLogin(request);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return "NG";
            }

            User user = foundUser.get();
            if (!user.isActive() || !PortalSessionFilter.knownRole(user.getRole())
                    || user.getAuthenticationVersion() != authenticatedUser.getAuthenticationVersion()
                    || !accountProtection.valid(user.getId(), outcome.protectionVersion())) {
                clearLogin(request);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return "NG";
            }

            PortalPrincipal principal = new PortalPrincipal(user.getId(), user.getUsername(),
                    user.getAuthenticationVersion(), outcome.protectionVersion());
            List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);

            HttpSession session = request.getSession(true);
            // 認証前のSession IDとCSRFトークンは、そのまま引き継がない。
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
            sessionLifecycle.start(session, user, outcome.protectionVersion());

            session.setAttribute("loginUserId", user.getId());
            session.setAttribute("loginUser", user.getUsername());
            session.setAttribute("loginRealName", user.getRealName());
            session.setAttribute("loginRole", user.getRole());
            session.setAttribute("loginDepartment", user.getDepartment());

            // フレームワークにも同じSessionの本人情報を保存する。パスワードは保存しない。
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(securityContext);
            securityContextRepository.saveContext(securityContext, request, response);
            return "OK";
        } catch (RuntimeException exception) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            return authenticationUnavailable(response, exception);
        }
    }

    private void clearLogin(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        session.removeAttribute("loginUserId");
        session.removeAttribute("loginUser");
        session.removeAttribute("loginRealName");
        session.removeAttribute("loginRole");
        session.removeAttribute("loginDepartment");
    }

    private String authenticationUnavailable(HttpServletResponse response, RuntimeException exception) {
        // 原因の型とSQLエラー番号だけを記録し、パスワードやSQLの値を出力しない。
        Throwable cause = exception;
        String sqlState = "-";
        int sqlErrorCode = 0;
        int depth = 0;
        while (cause != null && depth < 20) {
            if (cause instanceof SQLException) {
                SQLException sqlException = (SQLException) cause;
                sqlState = sqlException.getSQLState();
                sqlErrorCode = sqlException.getErrorCode();
            }
            if (cause.getCause() == null || cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
            depth++;
        }
        logger.error("認証サービス障害 correlationId={} exception={} cause={} sqlState={} sqlErrorCode={}",
                SecurityRequestContext.current().correlationId(), exception.getClass().getName(),
                cause.getClass().getName(), sqlState, sqlErrorCode);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        return "{\"success\":false,\"code\":\"AUTH_UNAVAILABLE\","
                + "\"message\":\"認証サービスを利用できません。システム管理者へ連絡してください。\"}";
    }
}
