package com.example.demo.config;

import com.example.demo.security.AuditService;
import com.example.demo.security.SecurityRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 業務画面の入口でSessionのログイン状態と管理者権限を確認する。 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LoginInterceptor.class);
    private final AuditService auditService;

    public LoginInterceptor(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {
        HttpSession session = request.getSession(false);

        // 認証・会話失効の確認後に設定されたSession属性を確認する。
        if (session == null || !(session.getAttribute("loginUserId") instanceof Long)
                || session.getAttribute("loginUser") == null) {
            if (isPageRequest(request)) {
                response.sendRedirect(request.getContextPath() + "/");
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"code\":\"AUTH_REQUIRED\","
                        + "\"redirectUrl\":\"/\",\"message\":\"ログインの有効期限が切れました。もう一度ログインしてください。\"}");
            }
            return false;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean administratorPage = path.equals("/users") || path.startsWith("/users/")
                || path.equals("/departments") || path.startsWith("/departments/")
                || path.equals("/api/dashboard");
        if (administratorPage && !"ADMIN".equals(session.getAttribute("loginRole"))) {
            try {
                auditService.security("ACCESS_REJECTED", SecurityRequestContext.actorId(), "REJECTED");
            } catch (RuntimeException exception) {
                logger.error("拒否操作の監査保存障害 correlationId={}",
                        SecurityRequestContext.current().correlationId());
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"code\":\"FORBIDDEN\","
                    + "\"message\":\"この操作を行う権限がありません。\"}");
            return false;
        }
        return true;
    }

    private boolean isPageRequest(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            return false;
        }
        return true;
    }
}
