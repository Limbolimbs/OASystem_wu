package com.example.demo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;

/** 監査に必要な識別情報だけを保持する。要求パラメーターは収集しない。 */
public final class SecurityRequestContext {
    public record Context(String correlationId, String sourceKey,java.util.Map<String,String> details) {
        public Context(String correlationId,String sourceKey){this(correlationId,sourceKey,null);}
    }
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private SecurityRequestContext() {}
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("識別化処理を初期化できません。"); }
    }
    public static void begin(String remoteAddress) { CURRENT.set(new Context(UUID.randomUUID().toString(), hash(remoteAddress == null ? "unknown" : remoteAddress))); }
    public static void begin(jakarta.servlet.http.HttpServletRequest request){CURRENT.set(new Context(UUID.randomUUID().toString(),hash(request.getRemoteAddr()==null?"unknown":request.getRemoteAddr()),com.example.audit.AccessFields.capture(request)));}
    public static Context current() {
        Context context = CURRENT.get();
        return context == null ? new Context(UUID.randomUUID().toString(), null) : context;
    }
    public static Long actorId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof PortalPrincipal principal ? principal.userId() : null;
    }
    public static void clear() { CURRENT.remove(); }
}
