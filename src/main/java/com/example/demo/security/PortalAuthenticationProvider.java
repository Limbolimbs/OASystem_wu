package com.example.demo.security;

import java.util.List;
import com.example.demo.service.UserService;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class PortalAuthenticationProvider implements AuthenticationProvider {
    private final LoginProtectionService protection;
    public PortalAuthenticationProvider(LoginProtectionService protection) { this.protection = protection; }

    @Override public Authentication authenticate(Authentication request) {
        String password = request.getCredentials() instanceof String value ? value : "";
        LoginProtectionService.Outcome outcome;
        try {
            String source = request.getDetails() instanceof org.springframework.security.web.authentication.WebAuthenticationDetails details
                    ? details.getRemoteAddress() : null;
            outcome = protection.login(request.getName(), password, source);
        } catch (RuntimeException exception) { throw new AuthenticationServiceException("認証サービスを利用できません。"); }
        if (outcome.retryAfter() > 0 || outcome.protectedLogin()) throw new LoginLimitedException(outcome.retryAfter());
        var user = outcome.user();
        if (user == null) throw new BadCredentialsException("ログインIDまたはパスワードが正しくありません。");
        var result = UsernamePasswordAuthenticationToken.authenticated(
                new PortalPrincipal(user.getId(), user.getUsername(), user.getAuthenticationVersion(),outcome.protectionVersion()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        result.setDetails(request.getDetails());
        return result;
    }

    @Override public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }
}
