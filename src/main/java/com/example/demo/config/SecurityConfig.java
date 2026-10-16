package com.example.demo.config;

import java.nio.charset.StandardCharsets;
import com.example.demo.security.*;
import com.example.demo.service.UserService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(SecuritySettings.class)
public class SecurityConfig {
    @Bean public java.time.Clock securityClock() { return java.time.Clock.systemUTC(); }
    @Bean public org.springframework.boot.web.servlet.ServletListenerRegistrationBean<SessionLifecycle> sessionListener(SessionLifecycle sessions) {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions);
    }
    @Bean public PasswordEncoder passwordEncoder() { return PasswordHashes.encoder(); }

    private static void failure(HttpServletResponse response, int status, String message, String code) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        // メッセージ・コードは固定文言のみ。例外・認証情報を返さない。
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }

    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http,
            PortalAuthenticationProvider provider, UserService users, SessionLifecycle sessions, AuditService audit,AccountProtection protection) throws Exception {
        sessions.setProtection(protection);
        http.authenticationProvider(provider)
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/favicon.ico", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/users/login", "/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/logout").authenticated()
                .requestMatchers("/users", "/users/**", "/departments", "/departments/**", "/api/dashboard").hasRole("ADMIN")
                .requestMatchers("/home", "/my-page", "/my-page/password", "/sales", "/sales/**", "/attendance", "/attendance/**", "/notices", "/notices/**", "/session/activity").authenticated()
                .anyRequest().denyAll())
            // 既定のsession保存・XORトークン。除外するPOSTは設けない。
            .csrf(csrf -> {})
            .formLogin(form -> form.loginPage("/").loginProcessingUrl("/users/login")
                .usernameParameter("userName").passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
                    var user = users.findById(principal.userId()).filter(value -> value.isActive()
                            && PortalSessionFilter.knownRole(value.getRole())).orElse(null);
                    if (user == null || user.getAuthenticationVersion() != principal.authenticationVersion()
                            || !protection.valid(principal.userId(),principal.protectionVersion())) {
                        request.getSession().invalidate();
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        response.setStatus(401); response.getWriter().write("NG"); return;
                    }
                    PortalSessionFilter.displayAttributes(request.getSession(), user);
                    try{sessions.start(request.getSession(), user,principal.protectionVersion());}
                    catch(RuntimeException unavailable){
                        request.getSession().invalidate();org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).error("会話登録の保存障害 correlationId={}",SecurityRequestContext.current().correlationId());
                        failure(response,503,"認証サービスを利用できません。再度ログインしてください。","AUTH_UNAVAILABLE");return;
                    }
                    response.setContentType("text/plain;charset=UTF-8"); response.getWriter().write("OK");
                })
                .failureHandler((request, response, exception) -> {
                    if (request.getSession(false) != null) request.getSession(false).removeAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    if (exception instanceof LoginLimitedException limited) {
                        if(limited.retryAfter()>0)response.setHeader("Retry-After", Long.toString(limited.retryAfter()));
                        failure(response, 429, "ログイン試行が制限されています。時間をおいて再度お試しいただくか、システム管理者へお問い合わせください。", "LOGIN_LIMITED");
                    } else if (exception instanceof org.springframework.security.authentication.AuthenticationServiceException) {
                        org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).error("認証サービス障害 correlationId={}", SecurityRequestContext.current().correlationId());
                        failure(response, 503, "認証サービスを利用できません。システム管理者へ連絡してください。", "AUTH_UNAVAILABLE");
                    } else { response.setContentType("text/plain;charset=UTF-8"); response.getWriter().write("NG"); }
                }))
            .logout(logout -> logout.logoutUrl("/logout").invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID").logoutSuccessHandler((request, response, authentication) -> {
                    try { audit.security("LOGOUT", authentication != null && authentication.getPrincipal() instanceof PortalPrincipal principal ? principal.userId() : null, "SUCCESS"); }
                    catch (RuntimeException exception) {
                        org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).error("ログアウトの監査保存障害 correlationId={}", SecurityRequestContext.current().correlationId());
                        failure(response, 503, "ログアウト済みですが監査を保存できません。システム管理者へ連絡してください。", "AUDIT_UNAVAILABLE"); return;
                    }
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":true,\"redirectUrl\":\"/\"}");
                }))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> {
                    if ("GET".equals(request.getMethod()) && !"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
                            && !request.getRequestURI().startsWith(request.getContextPath() + "/api/")) response.sendRedirect(request.getContextPath() + "/");
                    else failure(response, 401, "ログインの有効期限が切れました。もう一度ログインしてください。", "AUTH_REQUIRED");
                })
                .accessDeniedHandler((request, response, exception) -> {
                    try { audit.security(exception instanceof CsrfException ? "CSRF_REJECTED" : "ACCESS_REJECTED", SecurityRequestContext.actorId(), "REJECTED"); }
                    catch (RuntimeException storageException) { org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).error("拒否操作の監査保存障害 correlationId={}", SecurityRequestContext.current().correlationId()); }
                    failure(response, 403,
                    exception instanceof CsrfException ? "画面の有効期限が切れました。再表示してから操作してください。操作は実行していません。" : "この操作を行う権限がありません。",
                    exception instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN"); }))
            .addFilterBefore(new SecurityContextFilter(), org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .addFilterAfter(new PortalSessionFilter(users, sessions, audit), org.springframework.security.web.context.SecurityContextHolderFilter.class);
        return http.build();
    }
}
