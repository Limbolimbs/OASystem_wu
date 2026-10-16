package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;

class UserLogicalDeleteTest {
    @Test void inactiveDepartmentPreventsActivationBeforeCredentialRevocation() {
        UserRepository repository = mock(UserRepository.class);
        DepartmentService departments = mock(DepartmentService.class);
        var state = mock(com.example.demo.security.AccountSecurityState.class);
        User user = new User(); user.setId(2L); user.setRole("EMPLOYEE");
        user.setDepartment("旧営業部"); user.softDelete();
        var deletedAt = user.getDeletedAt();
        when(repository.findForUpdate(2L)).thenReturn(Optional.of(user));
        when(departments.requireActive("旧営業部")).thenThrow(new IllegalArgumentException("有効な部署を選択してください。"));
        var service = new UserService(repository, com.example.demo.security.PasswordHashes.encoder(), state, departments);
        var failure = assertThrows(IllegalStateException.class, () -> service.activate(2L));
        assertTrue(failure.getMessage().contains("所属部署"));
        assertFalse(user.isActive()); assertEquals(1, user.getDeleteFlag());
        assertEquals(deletedAt, user.getDeletedAt()); assertEquals(0L, user.getAuthenticationVersion());
        verify(state, never()).revoke(any()); verify(repository, never()).save(any());
    }

    @Test void validDepartmentAllowsActivationAndMissingEmployeeStillReturnsFalse() {
        UserRepository repository = mock(UserRepository.class);
        DepartmentService departments = mock(DepartmentService.class);
        var state = mock(com.example.demo.security.AccountSecurityState.class);
        User user = new User(); user.setId(2L); user.setDepartment("営業部"); user.softDelete();
        when(repository.findForUpdate(2L)).thenReturn(Optional.of(user));
        var service = new UserService(repository, com.example.demo.security.PasswordHashes.encoder(), state, departments);
        assertTrue(service.activate(2L)); assertTrue(user.isActive());
        verify(departments).requireActive("営業部"); verify(state).revoke(user);
        assertFalse(service.activate(99L));
    }

    @Test void deactivationKeepsIdentityAndActivationRestoresBothFlags() {
        UserRepository repository = mock(UserRepository.class);
        User user = new User(); user.setId(2L); user.setRole("EMPLOYEE");
        when(repository.findForUpdate(2L)).thenReturn(Optional.of(user));
        UserService service = new UserService(repository, com.example.demo.security.PasswordHashes.encoder(), mock(com.example.demo.security.AccountSecurityState.class), mock(DepartmentService.class));
        assertTrue(service.deactivate(2L));
        assertEquals(0, user.getStatus()); assertEquals(1, user.getDeleteFlag()); assertNotNull(user.getDeletedAt());
        var timestamp = user.getDeletedAt(); service.deactivate(2L);
        assertEquals(timestamp, user.getDeletedAt()); assertEquals(2L, user.getId());
        assertTrue(service.activate(2L));
        assertTrue(user.isActive()); assertEquals(0, user.getDeleteFlag()); assertNull(user.getDeletedAt());
        verify(repository, times(3)).save(user); verify(repository, never()).delete(any());
    }

    @Test void lastActiveAdministratorCannotBeDeactivated() {
        UserRepository repository = mock(UserRepository.class);
        User admin = new User(); admin.setId(1L); admin.setRole("ADMIN");
        when(repository.findForUpdate(1L)).thenReturn(Optional.of(admin));
        when(repository.countByRoleAndStatus("ADMIN", 1)).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> new UserService(repository, com.example.demo.security.PasswordHashes.encoder(), mock(com.example.demo.security.AccountSecurityState.class), mock(DepartmentService.class)).deactivate(1L));
        assertTrue(admin.isActive()); verify(repository, never()).save(any());
    }

    @Test void invalidAccountCannotLoginEvenIfRepositoryReturnsIt() {
        UserRepository repository = mock(UserRepository.class);
        User user = new User(); user.softDelete();
        when(repository.findByUsername("tanaka")).thenReturn(Optional.of(user));
        assertNull(new UserService(repository, com.example.demo.security.PasswordHashes.encoder(), mock(com.example.demo.security.AccountSecurityState.class), mock(DepartmentService.class)).findLoginUser("tanaka", "password"));
    }
}
