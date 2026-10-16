package com.example.control;

import java.time.Clock;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class ConsoleSecurity {
    @Bean SecurityFilterChain chain(HttpSecurity http,ConsoleProvider provider,ConsoleStore store,Clock clock,OperatorSettings settings,ConsoleMembers members) throws Exception {
        var boundary=new OncePerRequestFilter(){
            protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next) throws ServletException,java.io.IOException {
                String address=request.getRemoteAddr();
                if(address==null || !(address.equals("127.0.0.1") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1"))) { ConsoleGuard.error(response,403,"この接続では利用できません。");return; }
                ConsoleRequestContext.begin(request);response.setHeader("X-Request-ID",ConsoleRequestContext.current().id());
                try { next.doFilter(request,response); } finally { ConsoleRequestContext.clear(); }
            }
        };
        http.authenticationProvider(provider).requestCache(c->c.disable())
            .authorizeHttpRequests(a->a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET,"/","/enrollment","/css/**","/js/**","/favicon.ico").permitAll()
                .requestMatchers("/enrollment/**").permitAll()
                .requestMatchers(HttpMethod.POST,"/auth/login","/auth/logout").permitAll()
                .requestMatchers("/members","/api/members/**").hasRole("OWNER")
                .requestMatchers("/console","/sessions","/personal","/api/**").hasAnyRole("OWNER","OPERATOR","OBSERVER").anyRequest().denyAll())
            .csrf(c->{})
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; script-src 'self' https://code.jquery.com; style-src 'self'; connect-src 'self'; img-src 'self' data:; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"))
                .referrerPolicy(c->c.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .formLogin(f->f.loginPage("/").loginProcessingUrl("/auth/login").usernameParameter("operator").passwordParameter("password")
                .authenticationDetailsSource(ConsoleProvider.Details::new)
                .successHandler((request,response,auth)->{
                    var session=request.getSession();
                    try{store.startSession(session,(ConsoleIdentity)auth.getPrincipal());}
                    catch(RuntimeException unavailable){session.invalidate();org.springframework.security.core.context.SecurityContextHolder.clearContext();ConsoleGuard.error(response,503,"会話を登録できません。再度ログインしてください。");return;}
                    session.setAttribute("issued",clock.millis());session.setAttribute("active",clock.millis());response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"success\":true,\"redirectUrl\":\"/console\"}");})
                .failureHandler((request,response,exception)->{
                    if(exception instanceof ConsoleProvider.Limited limit){if(limit.seconds>0)response.setHeader("Retry-After",Long.toString(limit.seconds));ConsoleGuard.error(response,429,limit.seconds>0?"ログインが制限されています。時間をおいて再度お試しください。":"認証が保護されています。最高管理者又は本機の保守担当者へ確認してください。");}
                    else if(exception instanceof org.springframework.security.authentication.AuthenticationServiceException){ConsoleGuard.error(response,503,"認証サービスを利用できません。");org.slf4j.LoggerFactory.getLogger(ConsoleSecurity.class).error("管理認証の保存障害 correlationId={}",ConsoleRequestContext.current().id());}
                    else ConsoleGuard.error(response,401,"管理ID・パスワード・認証コードを確認してください。");}))
            .logout(l->l.logoutUrl("/auth/logout").deleteCookies("__Host-CONTROL_SESSION").logoutSuccessHandler((request,response,auth)->{
                try{if(auth!=null && auth.getPrincipal() instanceof ConsoleIdentity identity)store.logout(identity);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(ConsoleSecurity.class).error("管理ログアウトの保存障害 correlationId={}",ConsoleRequestContext.current().id());ConsoleGuard.error(response,503,"ログアウト済みですが監査を保存できません。");return;}
                response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"success\":true}");}))
            .exceptionHandling(e->e.authenticationEntryPoint((request,response,exception)->{if(request.getRequestURI().startsWith("/api/"))ConsoleGuard.error(response,401,"ログインしてください。");else response.sendRedirect("/");})
                .accessDeniedHandler((request,response,exception)->{try{members.denied();}catch(RuntimeException failure){ConsoleGuard.error(response,503,"拒否記録を保存できません。操作は実行していません。");return;}ConsoleGuard.error(response,403,"操作権限又は画面の安全情報を確認してください。再表示後に操作してください。");}))
            .addFilterBefore(boundary,SecurityContextHolderFilter.class).addFilterAfter(new ConsoleGuard(store,clock,settings),SecurityContextHolderFilter.class);
        return http.build();
    }
    @Bean org.springframework.boot.ApplicationRunner readiness(ConsoleStore store,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return arguments->{
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN('security_console_operator','security_console_action')",Integer.class)!=2) throw new IllegalStateException("管理用SQLの適用が必要です。");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='security_login_attempt' AND column_name IN('failure_stage','protection_version','protected_at','protection_reason')) OR (table_name='security_console_operator' AND column_name IN('failure_window_start','failure_stage','login_protected','protected_at','protection_reason')))",Integer.class)!=9) throw new IllegalStateException("13の認証保護SQLの適用が必要です。");
            store.register();
        };
    }
}
