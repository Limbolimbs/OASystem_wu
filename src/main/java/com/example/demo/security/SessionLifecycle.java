package com.example.demo.security;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.example.demo.entity.User;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;

@Component
public class SessionLifecycle implements HttpSessionListener {
    public static final String ISSUED = "securityIssuedAt";
    public static final String ACTIVE = "securityActiveAt";
    public static final String VERSION = "securityAuthenticationVersion";
    public static final String PROTECTION_VERSION = "securityProtectionVersion";
    private final Clock clock;
    private final SecuritySettings settings;
    private AccountProtection protection;
    private com.example.security.SessionRegistry registry;
    private final Map<Long, Set<HttpSession>> sessions = new ConcurrentHashMap<>();
    private final Map<HttpSession, Long> owners = new ConcurrentHashMap<>();
    public SessionLifecycle(Clock clock, SecuritySettings settings) { this.clock = clock; this.settings = settings; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setProtection(AccountProtection protection) { this.protection=protection; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setRegistry(com.example.security.SessionRegistry value) { registry=value; }

    public void start(HttpSession session, User user) {
        start(session,user,0);
    }
    public void start(HttpSession session, User user,long protectionVersion) {
        if(registry!=null){
            var context=SecurityRequestContext.current();
            String ref=registry.open(user.getId().toString(),user.getUsername(),user.getRole(),user.getAuthenticationVersion(),protectionVersion,context.correlationId(),context.details()==null?null:context.details().get("ip"));
            session.setAttribute(com.example.security.SessionRegistry.REF,ref);
        }
        long now = clock.millis();
        session.setAttribute(ISSUED, now); session.setAttribute(ACTIVE, now);
        session.setAttribute(VERSION, user.getAuthenticationVersion());
        session.setAttribute(PROTECTION_VERSION, protectionVersion);
        session.setAttribute("securityOwnerId", user.getId());
        session.setMaxInactiveInterval(Math.toIntExact(settings.getIdleTimeout().toSeconds()));
        sessions.computeIfAbsent(user.getId(), id -> ConcurrentHashMap.newKeySet()).add(session);
        owners.put(session, user.getId());
    }

    public String invalidReason(HttpSession session, User user) {
        if (session == null || !(session.getAttribute(ISSUED) instanceof Long issued)
                || !(session.getAttribute(ACTIVE) instanceof Long active)
                || !(session.getAttribute(VERSION) instanceof Long version)) return "SESSION_REVOKED";
        if (!user.isActive() || !PortalSessionFilter.knownRole(user.getRole()) || version != user.getAuthenticationVersion()) return "SESSION_REVOKED";
        if (protection != null && (!(session.getAttribute(PROTECTION_VERSION) instanceof Long generation)
                || !protection.valid(user.getId(),generation))) return "SESSION_PROTECTED";
        long now = clock.millis();
        if (now < issued || now < active || now - issued >= settings.getAbsoluteTimeout().toMillis()) return "SESSION_ABSOLUTE_EXPIRED";
        if (now - active >= settings.getIdleTimeout().toMillis()) return "SESSION_IDLE_EXPIRED";
        if(registry!=null && !registry.valid(binding(session,user),false))return "SESSION_REVOKED";
        return null;
    }

    private com.example.security.SessionRegistry.Binding binding(HttpSession session,User user){
        Object ref=session.getAttribute(com.example.security.SessionRegistry.REF),auth=session.getAttribute(VERSION),protectionGeneration=session.getAttribute(PROTECTION_VERSION);
        if(!(ref instanceof String reference)||!(auth instanceof Long version)||!(protectionGeneration instanceof Long generation))return null;
        return new com.example.security.SessionRegistry.Binding(reference,user.getId().toString(),user.getRole(),version,generation);
    }
    public void bind(HttpSession session,User user){if(registry!=null)registry.bind(binding(session,user));}
    public void clear(){if(registry!=null)registry.clear();}
    public void end(HttpSession session,String reason){if(registry!=null && session!=null && session.getAttribute(com.example.security.SessionRegistry.REF) instanceof String ref)registry.close(ref,reason);}
    public void touch(HttpSession session) {
        if(registry!=null && (!(session.getAttribute(com.example.security.SessionRegistry.REF) instanceof String ref)||!registry.touch(ref)))return;
        session.setAttribute(ACTIVE, clock.millis());
    }
    public void invalidateAll(Long userId) {
        Set<HttpSession> owned = sessions.remove(userId);
        if (owned != null) for (HttpSession session : owned) { owners.remove(session); try { session.invalidate(); } catch (IllegalStateException ignored) { } }
    }
    @Override public void sessionDestroyed(HttpSessionEvent event) {
        try{end(event.getSession(),"LOCAL_END");}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(SessionLifecycle.class).error("会話終了状態の保存障害");}
        Object id = owners.remove(event.getSession());
        if (id instanceof Long userId) {
            Set<HttpSession> owned = sessions.get(userId);
            if (owned != null) { owned.remove(event.getSession()); if (owned.isEmpty()) sessions.remove(userId, owned); }
        }
    }
}
