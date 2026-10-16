package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.DepartmentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class AccessControlServiceTest {
    @Test void usesDatabaseRoleAndDepartmentInsteadOfForgedSessionLabels() {
        UserRepository users = mock(UserRepository.class);
        DepartmentService departments = mock(DepartmentService.class);
        AccessControlService access = new AccessControlService(users, departments);
        User user = new User(); user.setId(2L); user.setRole("EMPLOYEE"); user.setDepartment("総務部");
        when(users.findById(2L)).thenReturn(Optional.of(user));
        MockHttpSession session = new MockHttpSession(); session.setAttribute("loginUserId", 2L);
        session.setAttribute("loginRole", "ADMIN"); session.setAttribute("loginDepartment", "営業部");
        User actor = access.currentUser(session).orElseThrow();
        assertFalse(access.isAdmin(actor)); assertFalse(access.canManageSales(actor));
        verify(departments).isSalesDepartment("総務部");
    }

    @Test void departmentPermissionChangeAppliesWithoutLoggingOut() {
        DepartmentService departments = mock(DepartmentService.class);
        AccessControlService access = new AccessControlService(mock(UserRepository.class), departments);
        User user = new User(); user.setRole("EMPLOYEE"); user.setDepartment("営業部");
        when(departments.isSalesDepartment("営業部")).thenReturn(true, false);
        assertTrue(access.canManageSales(user)); assertFalse(access.canManageSales(user));
        user.setRole("ADMIN"); assertTrue(access.canManageSales(user));
    }

    @Test void inactiveAccountInvalidatesSession() {
        UserRepository users = mock(UserRepository.class);
        AccessControlService access = new AccessControlService(users, mock(DepartmentService.class));
        User user = new User(); user.setStatus(0); when(users.findById(2L)).thenReturn(Optional.of(user));
        MockHttpSession session = new MockHttpSession(); session.setAttribute("loginUserId", 2L);
        assertTrue(access.currentUser(session).isEmpty()); assertTrue(session.isInvalid());
    }
}
