package com.example.demo.security;

import java.io.Serializable;
import java.security.Principal;

/** セッションにパスワード・ハッシュを保持しない認証主体。 */
public record PortalPrincipal(Long userId, String loginId, long authenticationVersion, long protectionVersion) implements Principal, Serializable {
    public PortalPrincipal(Long userId, String loginId) { this(userId, loginId, 0, 0); }
    public PortalPrincipal(Long userId, String loginId, long authenticationVersion) { this(userId,loginId,authenticationVersion,0); }
    @Override public String getName() { return loginId; }
}
