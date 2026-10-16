package com.example.demo.security;

import java.io.IOException;
import java.util.List;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 表示用session属性を認証主体から再生成し、無効・不明roleを拒否する。 */
public class PortalSessionFilter extends OncePerRequestFilter {
    private final UserService users;
    private final SessionLifecycle sessions;
    private final AuditService audit;
    public PortalSessionFilter(UserService users, SessionLifecycle sessions, AuditService audit) { this.users = users; this.sessions = sessions; this.audit = audit; }

    public static boolean knownRole(String role) {
        return role != null && List.of("ADMIN", "GENERAL_AFFAIRS", "EMPLOYEE").contains(role);
    }

    public static void displayAttributes(HttpSession session, User user) {
        session.setAttribute("loginUserId", user.getId());
        session.setAttribute("loginUser", user.getUsername());
        session.setAttribute("loginRealName", user.getRealName());
        session.setAttribute("loginRole", user.getRole());
        session.setAttribute("loginDepartment", user.getDepartment());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean interactive = false;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PortalPrincipal principal) {
            User user = users.findById(principal.userId()).filter(User::isActive).orElse(null);
            String invalid;
            try { invalid = user == null ? "SESSION_REVOKED" : sessions.invalidReason(request.getSession(false), user); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(PortalSessionFilter.class).error("会話保護状態の確認障害 correlationId={}",SecurityRequestContext.current().correlationId());
                response.setStatus(503);response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"code\":\"SECURITY_UNAVAILABLE\",\"message\":\"認証の安全情報を確認できません。操作は実行していません。\"}");return;
            }
            if (invalid != null) {
                try{sessions.end(request.getSession(false),invalid);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(PortalSessionFilter.class).error("会話終了状態の保存障害 correlationId={}",SecurityRequestContext.current().correlationId());}
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                Cookie cookie = new Cookie("JSESSIONID", ""); cookie.setPath("/"); cookie.setMaxAge(0); cookie.setHttpOnly(true); cookie.setSecure(request.isSecure()); response.addCookie(cookie);
                try { audit.security(invalid, principal.userId(), "REJECTED"); }
                catch (RuntimeException exception) {
                    org.slf4j.LoggerFactory.getLogger(PortalSessionFilter.class).error("会話失効の監査保存障害 correlationId={}", SecurityRequestContext.current().correlationId());
                    response.setStatus(503); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"success\":false,\"code\":\"AUDIT_UNAVAILABLE\",\"message\":\"認証を終了しました。システム管理者へ連絡してください。\"}"); return;
                }
                if ("GET".equals(request.getMethod()) && !"XMLHttpRequest".equals(request.getHeader("X-Requested-With")) && !request.getRequestURI().startsWith(request.getContextPath() + "/api/")) response.sendRedirect(request.getContextPath() + "/");
                else { response.setStatus(401); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"success\":false,\"code\":\"" + invalid + "\",\"message\":\"ログインの有効期限が切れました。もう一度ログインしてください。\"}"); }
                return;
            } else {
                var current = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
                SecurityContextHolder.getContext().setAuthentication(current);
                displayAttributes(request.getSession(), user);
                sessions.bind(request.getSession(),user);
                String path = request.getServletPath();
                boolean background = path.startsWith("/api/") || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || "1".equals(request.getHeader("X-Background-Request"));
                interactive = !background;
            }
        } else if (request.getSession(false) != null) {
            for (String name : List.of("loginUserId", "loginUser", "loginRealName", "loginRole", "loginDepartment")) {
                request.getSession(false).removeAttribute(name);
            }
        }
        try{
            chain.doFilter(request, response);
            if (interactive && response.getStatus() < 500 && response.getStatus() != 401 && response.getStatus() != 403 && request.getSession(false) != null) {
                try { sessions.touch(request.getSession(false)); } catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(PortalSessionFilter.class).error("会話有効操作の保存障害 correlationId={}",SecurityRequestContext.current().correlationId()); }
            }
        }finally{sessions.clear();}
    }
}
