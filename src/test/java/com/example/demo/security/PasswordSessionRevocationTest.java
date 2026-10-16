package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.Optional;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PasswordSessionRevocationTest {
    @Test void committedPasswordChangeRevokesEveryRegisteredSessionNotBeforeCommit() {
        var life = new SessionLifecycle(Clock.systemUTC(), new SecuritySettings());
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        var state = new AccountSecurityState(jdbc, life);
        var repository = mock(UserRepository.class);
        var encoder = PasswordHashes.encoder();
        var user = new User(); user.setId(2L); user.setRole("EMPLOYEE"); user.setPassword(encoder.encode("old-test"));
        when(repository.findForUpdate(2L)).thenReturn(Optional.of(user));
        var first = new MockHttpSession(); var otherDevice = new MockHttpSession();
        life.start(first, user); life.start(otherDevice, user);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            new UserService(repository, encoder, state, mock(com.example.demo.service.DepartmentService.class)).changePassword(2L, "old-test", "NewSecret123!");
            assertFalse(first.isInvalid()); assertFalse(otherDevice.isInvalid());
            assertEquals(1, user.getAuthenticationVersion());
            assertTrue(encoder.matches("NewSecret123!", user.getPassword()));
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCommit());
            assertTrue(first.isInvalid()); assertTrue(otherDevice.isInvalid());
        } finally { TransactionSynchronizationManager.clear(); }
    }
}
