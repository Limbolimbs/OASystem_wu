package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import com.example.demo.entity.User;

class AccountProtectionTest {
    @SuppressWarnings("unchecked")
    @Test void protectedAndPreviouslyReleasedSessionsCannotBeUsedForReadsOrWrites(){
        var jdbc=mock(JdbcTemplate.class);var protection=new AccountProtection(jdbc);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(new AccountProtection.State(true,1)));
        assertFalse(protection.valid(1L,0));
        var user=new User();user.setId(1L);user.setRole("EMPLOYEE");
        var life=new SessionLifecycle(java.time.Clock.systemUTC(),new SecuritySettings());life.setProtection(protection);
        var session=new MockHttpSession();life.start(session,user,0);
        assertEquals("SESSION_PROTECTED",life.invalidReason(session,user));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(new AccountProtection.State(false,2)));
        assertFalse(protection.valid(1L,0));assertTrue(protection.valid(1L,2));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new PortalPrincipal(1L,"employee",0,0),null,List.of()));
        try{assertThrows(ResponseStatusException.class,protection::requireCurrentMutation);}finally{SecurityContextHolder.clearContext();}
        assertEquals("SESSION_PROTECTED",life.invalidReason(session,user));
        life.start(new MockHttpSession(),user,2);
    }
}
