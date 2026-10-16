package com.example.control;
import java.util.List;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ConsoleProvider implements AuthenticationProvider {
    public static final class Details extends WebAuthenticationDetails {
        final String otp;
        public Details(HttpServletRequest request) { super(request); otp=request.getParameter("otp"); }
    }
    public static final class Limited extends AuthenticationException {
        final long seconds;
        Limited(long seconds) { super("ログインが制限されています。"); this.seconds=seconds; }
    }
    private final ConsoleStore store; private final OperatorSettings settings;
    public ConsoleProvider(ConsoleStore store,OperatorSettings settings) { this.store=store; this.settings=settings; }
    public Authentication authenticate(Authentication request) {
        ConsoleStore.LoginResult result;
        try { result=store.login(request.getName(),request.getCredentials() instanceof String p?p:"",request.getDetails() instanceof Details d?d.otp:null); }
        catch(RuntimeException failure) { throw new AuthenticationServiceException("認証サービスを利用できません。"); }
        if(result.retryAfter()>0 || result.protectedLogin()) throw new Limited(result.retryAfter());
        if(!result.accepted()) throw new BadCredentialsException("認証情報を確認してください。");
        var identity=result.identity()==null?new ConsoleIdentity(settings.getOperatorId(),settings.getLoginId()):result.identity();
        return UsernamePasswordAuthenticationToken.authenticated(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+identity.role())));
    }
    public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
