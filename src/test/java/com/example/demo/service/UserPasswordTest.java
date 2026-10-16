package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.PasswordHashes;
import org.junit.jupiter.api.Test;

class UserPasswordTest {
    @Test void passwordChangeChecksPolicyAndCurrentPasswordAndRevokesExistingSessions() {
        var repository = mock(UserRepository.class); var state = mock(com.example.demo.security.AccountSecurityState.class);
        var encoder = PasswordHashes.encoder(); var user = new User(); user.setId(2L); user.setRole("EMPLOYEE"); user.setPassword(encoder.encode("123"));
        when(repository.findForUpdate(2L)).thenReturn(Optional.of(user));
        var service = new UserService(repository, encoder, state, mock(DepartmentService.class));
        assertThrows(IllegalArgumentException.class, () -> service.changePassword(2L, "123", "short"));
        assertThrows(IllegalArgumentException.class, () -> service.changePassword(2L, "wrong", "NewSecret123!"));
        verify(state, never()).revoke(any());
        service.changePassword(2L, "123", "NewSecret123!");
        assertTrue(encoder.matches("NewSecret123!", user.getPassword())); verify(state).revoke(user);
        assertThrows(IllegalArgumentException.class, () -> service.save(user));
    }
    @Test void saltedHashesAreDifferentAndNoPlaintextOrHashCanAuthenticateAsPassword() {
        var encoder = PasswordHashes.encoder();
        String hash = encoder.encode("123");
        assertTrue(PasswordHashes.isSupported(hash));
        assertNotEquals(hash, encoder.encode("123"));
        var repository = mock(UserRepository.class);
        var user = new User(); user.setRole("EMPLOYEE"); user.setPassword(hash);
        when(repository.findByUsername("tanaka")).thenReturn(Optional.of(user));
        var service = new UserService(repository, encoder, mock(com.example.demo.security.AccountSecurityState.class), mock(DepartmentService.class));
        assertSame(user, service.findLoginUser("tanaka", "123"));
        assertNull(service.findLoginUser("tanaka", "wrong"));
        assertNull(service.findLoginUser("tanaka", hash));
        user.setPassword("123");
        assertNull(service.findLoginUser("tanaka", "123"));
        user.setPassword("{noop}123");
        assertNull(service.findLoginUser("tanaka", "123"));
        user.setPassword(hash); user.setRole("UNKNOWN");
        assertNull(service.findLoginUser("tanaka", "123"));
        user.setRole("EMPLOYEE"); user.setStatus(0);
        assertNull(service.findLoginUser("tanaka", "123"));
    }

    @Test void passwordCannotBeReusedAndRejectedChangeDoesNotRevokeSessions() {
        var repository = mock(UserRepository.class); var state = mock(com.example.demo.security.AccountSecurityState.class);
        var encoder = PasswordHashes.encoder(); var user = new User(); user.setId(2L);
        String original = encoder.encode("SameSecret123!"); user.setPassword(original);
        when(repository.findForUpdate(2L)).thenReturn(Optional.of(user));
        var service = new UserService(repository, encoder, state, mock(DepartmentService.class));
        assertThrows(IllegalArgumentException.class, () -> service.changePassword(2L, "SameSecret123!", "SameSecret123!"));
        assertEquals(original, user.getPassword()); verify(state, never()).revoke(any());
    }

    @Test void encodingDoesNotTruncateLongUnicodePasswordsAndHashDoesNotLeakToJson() {
        var encoder = PasswordHashes.encoder();
        String value = "Ab1!" + "界".repeat(124);
        String hash = encoder.encode(value);
        assertTrue(encoder.matches(value, hash));
        assertFalse(encoder.matches(value.substring(0, 100), hash));
        var user = new User(); user.setPassword(hash); user.setUsername("EMP0001");
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(user);
        assertFalse(json.contains(hash)); assertFalse(json.contains("password"));
    }
}
