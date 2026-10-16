package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;

class UserRegistrationTest {
    @Test void registrationChecksDepartmentInsideServiceBeforeEncodingOrSaving() {
        UserRepository repository = mock(UserRepository.class);
        DepartmentService departments = mock(DepartmentService.class);
        var encoder = mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        User candidate = user("ValidPassword123!"); candidate.setDepartment("無効部署");
        when(departments.requireActive("無効部署")).thenThrow(new IllegalArgumentException("有効な部署を選択してください。"));
        var service = new UserService(repository, encoder, mock(com.example.demo.security.AccountSecurityState.class), departments);
        clearInvocations(encoder);
        assertThrows(IllegalArgumentException.class, () -> service.save(candidate));
        verify(departments).requireActive("無効部署");
        verifyNoInteractions(encoder); verify(repository, never()).save(any());
        assertEquals("ValidPassword123!", candidate.getPassword());
    }

    private User user(String password) {
        User user = new User(); user.setUsername(" 000123 "); user.setPassword(password);
        user.setRealName("田中 太郎"); user.setDepartment(""); user.setRole("EMPLOYEE");
        return user;
    }

    @Test void directServiceCallCannotSaveAWeakNewPassword() {
        UserRepository repository = mock(UserRepository.class);
        assertThrows(IllegalArgumentException.class, () -> new UserService(repository, com.example.demo.security.PasswordHashes.encoder(), mock(com.example.demo.security.AccountSecurityState.class), mock(DepartmentService.class)).save(user("123")));
        verify(repository, never()).save(any());
    }

    @Test void trimsLoginIdButPreservesPasswordAndLeadingZeros() {
        UserRepository repository = mock(UserRepository.class);
        User user = user(" Abcdefgh1234 ");
        when(repository.save(user)).thenReturn(user);
        var encoder = com.example.demo.security.PasswordHashes.encoder();
        assertSame(user, new UserService(repository, encoder, mock(com.example.demo.security.AccountSecurityState.class), mock(DepartmentService.class)).save(user));
        assertEquals("000123", user.getUsername());
        assertTrue(com.example.demo.security.PasswordHashes.isSupported(user.getPassword()));
        assertTrue(encoder.matches(" Abcdefgh1234 ", user.getPassword()));
        assertFalse(encoder.matches("Abcdefgh1234", user.getPassword()));
    }
}
