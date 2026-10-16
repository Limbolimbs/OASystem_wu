package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import com.example.demo.entity.User;
import com.example.demo.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProfileServiceTest {
    UserService users = mock(UserService.class);
    DepartmentService departments = mock(DepartmentService.class);
    LoginProtectionService protection = mock(LoginProtectionService.class);
    ProfileService profiles = new ProfileService(users, departments, protection);
    User actor;
    PortalPrincipal principal = new PortalPrincipal(2L, "old-login-name");

    @BeforeEach void setup() {
        actor = new User(); actor.setId(2L); actor.setUsername("EMP0002");
        actor.setRealName("確認用社員"); actor.setDepartment("総務部"); actor.setRole("EMPLOYEE");
        actor.setPassword("never-expose-this");
        when(users.findById(2L)).thenReturn(Optional.of(actor));
    }

    @Test void profileUsesLatestCompanyDataWithoutReturningEntityOrSecurityFields() {
        var view = profiles.profile(principal);
        assertEquals("EMP0002", view.loginId()); assertEquals("一般社員", view.roleLabel());
        assertFalse(view.admin()); assertFalse(view.sales()); assertFalse(view.approver());
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(view);
        assertFalse(json.contains("never-expose-this")); assertFalse(json.contains("password"));
        assertFalse(json.contains("authenticationVersion"));
        actor.setRole("GENERAL_AFFAIRS");
        assertEquals("総務担当", profiles.profile(principal).roleLabel());
        assertTrue(profiles.profile(principal).approver()); assertFalse(profiles.profile(principal).admin());
        when(departments.isSalesDepartment("総務部")).thenReturn(true);
        assertTrue(profiles.profile(principal).sales());
        actor.setRole("ADMIN"); assertTrue(profiles.profile(principal).admin());
    }

    @Test void missingInactiveUnknownRoleAndOldAuthenticationAreRejected() {
        assertThrows(ResponseStatusException.class, () -> profiles.profile(null));
        actor.setAuthenticationVersion(1);
        assertThrows(ResponseStatusException.class, () -> profiles.profile(principal));
        actor.setAuthenticationVersion(0); actor.setRole("UNKNOWN");
        assertThrows(ResponseStatusException.class, () -> profiles.profile(principal));
        actor.setRole("EMPLOYEE"); actor.setStatus(0);
        assertThrows(ResponseStatusException.class, () -> profiles.profile(principal));
        verifyNoInteractions(protection);
    }

    @Test void policyConfirmationAndEmptyCurrentPasswordFailBeforeAuthenticationOrUpdate() {
        assertThrows(IllegalArgumentException.class, () -> change("old", "short", "short"));
        assertThrows(IllegalArgumentException.class, () -> change("old", "NewSecret123!", "different"));
        assertThrows(IllegalArgumentException.class, () -> change("", "NewSecret123!", "NewSecret123!"));
        assertThrows(IllegalArgumentException.class, () -> change("old", "abcdefghijkl", "abcdefghijkl"));
        verifyNoInteractions(protection); verify(users, never()).changePassword(any(), any(), any());
    }

    private LoginProtectionService.Outcome change(String current, String next, String confirm) {
        return profiles.changePassword(principal, current, next, confirm, "192.0.2.25");
    }

    @Test void failedOrWrongIdentityReauthenticationNeverUpdatesPassword() {
        when(protection.verifyPassword(actor, "bad", "192.0.2.25"))
                .thenReturn(new LoginProtectionService.Outcome(null, 0));
        assertThrows(IllegalArgumentException.class, () -> change("bad", "NewSecret123!", "NewSecret123!"));
        User other = new User(); other.setId(999L);
        when(protection.verifyPassword(actor, "old", "192.0.2.25"))
                .thenReturn(new LoginProtectionService.Outcome(other, 0));
        assertThrows(IllegalArgumentException.class, () -> change("old", "NewSecret123!", "NewSecret123!"));
        verify(users, never()).changePassword(any(), any(), any());
    }

    @Test void activeRestrictionAndManualProtectionNeverReachPasswordMutation() {
        for (var outcome : new LoginProtectionService.Outcome[]{new LoginProtectionService.Outcome(null, 900),
                new LoginProtectionService.Outcome(null, 0, true)}) {
            when(protection.verifyPassword(actor, "old", "192.0.2.25")).thenReturn(outcome);
            assertSame(outcome, change("old", "NewSecret123!", "NewSecret123!"));
        }
        verify(users, never()).changePassword(any(), any(), any());
    }

    @Test void successfulReauthenticationUpdatesOnlyPrincipalOwnerViaAuditedExistingService() {
        when(protection.verifyPassword(actor, "old", "192.0.2.25"))
                .thenReturn(new LoginProtectionService.Outcome(actor, 0));
        change("old", "NewSecret123!", "NewSecret123!");
        verify(users).changePassword(2L, "old", "NewSecret123!");
    }

    @Test void authenticationStorageOutageDoesNotExposeExceptionOrMutatePassword() {
        when(protection.verifyPassword(actor, "old", "192.0.2.25"))
                .thenThrow(new IllegalStateException("private-database-details"));
        var error = assertThrows(ResponseStatusException.class,
                () -> change("old", "NewSecret123!", "NewSecret123!"));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(error.getMessage().contains("private-database-details"));
        verify(users, never()).changePassword(any(), any(), any());
    }
}
