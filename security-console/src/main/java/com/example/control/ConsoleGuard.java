package com.example.control;

import java.io.IOException;
import java.time.Clock;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;

/** 専用cookieと独立認証を要求。本機以外は公開しない。 */
public class ConsoleGuard extends OncePerRequestFilter {
    private final ConsoleStore store; private final Clock clock; private final OperatorSettings settings;
    ConsoleGuard(ConsoleStore store,Clock clock,OperatorSettings settings) { this.store=store;this.clock=clock;this.settings=settings; }
    static void error(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\""+message+"\"}");
    }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof ConsoleIdentity identity) {
            var session=request.getSession(false); long current=clock.millis();
            boolean invalid=session==null || !(session.getAttribute("issued") instanceof Long) || !(session.getAttribute("active") instanceof Long);
            if(!invalid) { long issued=(Long)session.getAttribute("issued"),active=(Long)session.getAttribute("active"); invalid=current<issued || current<active || current-issued>=3600000 || current-active>=600000; }
            try { invalid=invalid || !store.valid(identity) || !store.sessionValid(session,identity); }
            catch(RuntimeException failure) { error(response,503,"安全情報を確認できません。操作を実行していません。"); return; }
            if(invalid) {
                try{store.endSession(session);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(ConsoleGuard.class).error("会話終了状態の保存障害");}
                if(session!=null) session.invalidate(); SecurityContextHolder.clearContext();
                Cookie cookie=new Cookie("__Host-CONTROL_SESSION",""); cookie.setPath("/");cookie.setSecure(true);cookie.setHttpOnly(true);cookie.setMaxAge(0);response.addCookie(cookie);
                if(request.getRequestURI().startsWith("/api/") || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) error(response,401,"認証の有効期限が切れました。再ログインしてください。");
                else response.sendRedirect("/"); return;
            }
            store.bindSession(session,identity);
        } else if(auth!=null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            SecurityContextHolder.clearContext(); error(response,403,"この認証では利用できません。"); return;
        }
        try{
            chain.doFilter(request,response);
            if(response.getStatus()<400 && (java.util.Set.of("/console","/sessions","/members","/personal","/api/activity").contains(request.getRequestURI()) || request.getMethod().equals("POST")) && request.getSession(false)!=null && SecurityContextHolder.getContext().getAuthentication()!=null && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof ConsoleIdentity){
                try{if(store.touchSession(request.getSession(false)))request.getSession(false).setAttribute("active",clock.millis());}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(ConsoleGuard.class).error("会話有効操作の保存障害");}
            }
        }finally{store.clearSession();}
    }
}
