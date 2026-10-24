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
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(SecuritySettings.class)
public class SecurityConfig {
    @Bean public java.time.Clock securityClock() { return java.time.Clock.systemUTC(); }
    @Bean public org.springframework.boot.web.servlet.ServletListenerRegistrationBean<SessionLifecycle> sessionListener(SessionLifecycle sessions) {
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions);
    }
    @Bean public PasswordEncoder passwordEncoder() { return PasswordHashes.encoder(); }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        java.util.List<SessionAuthenticationStrategy> strategies =
                new java.util.ArrayList<SessionAuthenticationStrategy>();
        strategies.add(new ChangeSessionIdAuthenticationStrategy());
        strategies.add(new CsrfAuthenticationStrategy(csrfTokenRepository));
        return new CompositeSessionAuthenticationStrategy(strategies);
    }

    private static void failure(HttpServletResponse response, int status, String message, String code) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        // メッセージ・コードは固定文言のみ。例外・認証情報を返さない。
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"code\":\"" + code + "\"}");
    }

    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http,
            UserService users, SessionLifecycle sessions, AuditService audit, AccountProtection protection,
            SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository) throws Exception {
        sessions.setProtection(protection);
        http.requestCache(cache -> cache.disable())
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/favicon.ico", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/users/login", "/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/logout").authenticated()
                // 業務URLのSession確認・管理者判定はLoginInterceptorで行う。
                .requestMatchers("/users", "/users/**", "/departments", "/departments/**", "/api/dashboard").permitAll()
                .requestMatchers("/home", "/my-page", "/my-page/password", "/sales", "/sales/**", "/attendance", "/attendance/**", "/notices", "/notices/**", "/session/activity").permitAll()
                .anyRequest().denyAll())
            // 既定のsession保存・XORトークン。除外するPOSTは設けない。
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
            // ログイン要求をFilterで処理せず、MVCのLoginControllerへ渡す。
            .formLogin(form -> form.disable())
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
