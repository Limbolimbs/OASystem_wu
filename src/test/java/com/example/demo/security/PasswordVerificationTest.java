package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.*;
import java.util.Optional;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** 再認証も既存のACCOUNT/SOURCE行を使い、別カウンターで制限を回避させない。 */
class PasswordVerificationTest {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UserService users = mock(UserService.class);
    AuditService audit = mock(AuditService.class);
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    Clock clock = Clock.fixed(Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC);
    LoginProtectionService service = new LoginProtectionService(jdbc, users, audit, clock, new SecuritySettings(), manager);
    User user = new User();
    int failures;
    boolean frozen;
    LocalDateTime blocked;

    @BeforeEach @SuppressWarnings("unchecked") void prepare() {
        user.setId(2L); user.setUsername("EMP0002"); user.setRole("EMPLOYEE");
        when(users.findByUsername("EMP0002")).thenReturn(Optional.of(user));
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any())).thenAnswer(call -> {
            boolean account = "ACCOUNT".equals(call.getArgument(2));
            ResultSet row = mock(ResultSet.class);
            when(row.getInt("attempts")).thenReturn(account ? failures : 0);
            when(row.getObject("window_start", LocalDateTime.class)).thenReturn(LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")));
            when(row.getObject("blocked_until", LocalDateTime.class)).thenReturn(account ? blocked : null);
            when(row.getInt("frozen")).thenReturn(account && frozen ? 1 : 0);
            return ((RowMapper<?>) call.getArgument(1)).mapRow(row, 0);
        });
    }

    @Test void fifthCurrentPasswordFailureUsesSameAccountKeyAndFifteenMinuteRestriction() {
        failures = 4;
        var result = service.verifyPassword(user, "wrong", "192.0.2.25");
        assertEquals(900, result.retryAfter()); assertNull(result.user());
        verify(audit).record(eq("PASSWORD_VERIFY_BLOCKED"), eq(2L), eq("ACCOUNT"), eq("2"), eq("REJECTED"), isNull(),
                eq(SecurityRequestContext.hash("user:2")));
        verify(jdbc).update(startsWith("UPDATE security_login_attempt SET attempts="), eq(5), any(), any(), eq("ACCOUNT"),
                eq(SecurityRequestContext.hash("user:2")));
        verify(manager).commit(any());
    }

    @Test void blockedAndFrozenAccountsDoNotCheckPasswordOrIncrementFailures() {
        blocked = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).plusMinutes(10);
        assertEquals(600, service.verifyPassword(user, "anything", "192.0.2.25").retryAfter());
        blocked = null; frozen = true;
        assertTrue(service.verifyPassword(user, "anything", "192.0.2.25").protectedLogin());
        verify(users, never()).findLoginUser(any(), any());
        verify(jdbc, never()).update(startsWith("UPDATE security_login_attempt SET attempts="), any(), any(), any(), eq("ACCOUNT"), any());
    }

    @Test void successfulVerificationUsesDedicatedAuditNameNotLoginSuccess() {
        when(users.findLoginUser("EMP0002", "right")).thenReturn(user);
        assertSame(user, service.verifyPassword(user, "right", "192.0.2.25").user());
        verify(audit).record(eq("PASSWORD_VERIFY_SUCCESS"), eq(2L), eq("ACCOUNT"), eq("2"), eq("SUCCESS"), isNull(), any());
        verify(audit, never()).record(eq("LOGIN_SUCCESS"), any(), any(), any(), any(), any(), any());
        assertSame(user, service.login("EMP0002", "right", "192.0.2.25").user());
        verify(audit).record(eq("LOGIN_SUCCESS"), eq(2L), eq("ACCOUNT"), eq("2"), eq("SUCCESS"), isNull(), any());
    }
}
