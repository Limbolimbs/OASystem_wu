package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.example.demo.config.SecurityConfig;
import com.example.demo.controller.*;
import com.example.demo.entity.User;
import com.example.demo.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 実際のSecurityFilterChainを通して検証。業務Controllerの単体試験とは分離する。 */
@WebMvcTest({UserController.class, HomeController.class, NoticeController.class, SessionController.class, ProfileController.class})
@Import({SecurityConfig.class, PortalAuthenticationProvider.class, SessionLifecycle.class})
class SecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserService users;
    @MockitoBean DepartmentService departments;
    @MockitoBean AccessControlService access;
    @MockitoBean NoticeService notices;
    @MockitoBean ProfileService profiles;
    @MockitoBean LoginProtectionService protection;
    @MockitoBean AuditService audit;
    @MockitoBean AccountProtection accountProtection;
    @MockitoBean com.example.security.SessionRegistry registry;
    @Autowired SessionLifecycle sessions;
    User admin;

    @BeforeEach void prepare() {
        when(accountProtection.valid(anyLong(),anyLong())).thenReturn(true);
        when(registry.valid(any(),anyBoolean())).thenReturn(true);
        when(registry.open(anyString(),anyString(),anyString(),anyLong(),anyLong(),any(),any())).thenReturn("00000000-0000-4000-8000-000000000001");
        when(registry.touch(anyString())).thenReturn(true);
        admin = new User(); admin.setId(1L); admin.setUsername("admin"); admin.setRealName("管理者");
        admin.setRole("ADMIN"); admin.setDepartment("総務部");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findLoginUser("admin", "123")).thenReturn(admin);
        when(protection.login(anyString(), anyString(), any())).thenAnswer(call ->
            new LoginProtectionService.Outcome("admin".equals(((String)call.getArgument(0)).trim()) && "123".equals(call.getArgument(1)) ? admin : null, 0));
    }

    RequestPostProcessor signedIn() {
        RequestPostProcessor signed = authentication(UsernamePasswordAuthenticationToken.authenticated(
                new PortalPrincipal(1L, "admin"), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        return request -> { sessions.start(request.getSession(), admin); return signed.postProcessRequest(request); };
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/login", "/logout", "/users/save", "/users/2/role", "/users/2/department",
        "/users/2/deactivate", "/users/2/activate", "/departments", "/departments/2", "/sales/save",
        "/attendance/clock-in", "/attendance/clock-out", "/attendance/break-start", "/attendance/break-end",
        "/attendance/manage/requests", "/attendance/manage/review/2/APPROVED", "/attendance/manage/review/2/REJECTED",
        "/attendance/manage/requests/2/cancel", "/attendance/manage/policy", "/attendance/manage/schedules",
        "/attendance/manage/holidays", "/attendance/manage/holidays/2026-10-14/delete", "/attendance/manage/schedules/2/delete",
        "/notices/save", "/notices/2/deactivate", "/notices/2/restore", "/notices/2/read", "/my-page/password"})
    void everyWriteRejectsMissingAndInvalidTokenBeforeBusinessCode(String url) throws Exception {
        mvc.perform(post(url).with(signedIn()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post(url).with(signedIn()).with(csrf().useInvalidToken()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verify(users, never()).save(any()); verify(notices, never()).save(any(), any());
        verify(profiles, never()).changePassword(any(), any(), any(), any(), any());
    }

    @Test void legacyShortPasswordLoginRotatesSessionAndStoresAuthenticationWithoutCredentials() throws Exception {
        var initial = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) initial.getRequest().getSession();
        String oldId = session.getId();
        mvc.perform(post("/users/login").session(session).with(csrf()).param("userName", " admin ").param("password", "123"))
            .andExpect(status().isOk()).andExpect(content().string("OK"));
        assertNotEquals(oldId, session.getId());
        assertEquals(1L, session.getAttribute("loginUserId"));
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertInstanceOf(PortalPrincipal.class, context.getAuthentication().getPrincipal());
        assertNull(context.getAuthentication().getCredentials());
        mvc.perform(get("/users/new").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/request-security.js")));
    }

    @Test void myPageRequiresRealAuthenticationNotLegacyDisplayAttributes() throws Exception {
        var fake = new MockHttpSession(); fake.setAttribute("loginUserId", 999L);
        mvc.perform(get("/my-page").session(fake).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/my-page/password").with(csrf()).header("X-Requested-With", "XMLHttpRequest")
                .param("currentPassword", "old").param("newPassword", "NewSecret123!").param("passwordConfirmation", "NewSecret123!"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }

    @Test void myPageIsAvailableToEmployeeAndDoesNotCacheIdentityOrExposeSensitiveUserFields() throws Exception {
        admin.setRole("EMPLOYEE");
        when(profiles.profile(any())).thenReturn(new ProfileService.Profile("EMP0001", "本人", "総務部", "一般社員", null, false, false, false));
        mvc.perform(get("/my-page").with(signedIn()).param("userId", "999"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EMP0001")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("authenticationVersion"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        verify(profiles).profile(new PortalPrincipal(1L, "admin"));
    }

    @Test void employeeCanChangeOnlyOwnPasswordEvenWhenForgingUserIdAndRole() throws Exception {
        admin.setRole("EMPLOYEE");
        when(profiles.changePassword(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new LoginProtectionService.Outcome(admin, 0));
        var session = new MockHttpSession();
        mvc.perform(post("/my-page/password").session(session).with(signedIn()).with(csrf())
                .header("X-Requested-With", "XMLHttpRequest").param("userId", "999").param("role", "ADMIN")
                .param("currentPassword", "123").param("newPassword", "NewSecret123!").param("passwordConfirmation", "NewSecret123!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.redirectUrl").value("/"))
                .andExpect(cookie().maxAge("JSESSIONID", 0)).andExpect(header().string("Cache-Control", "no-store"));
        verify(profiles).changePassword(eq(new PortalPrincipal(1L, "admin")), eq("123"),
                eq("NewSecret123!"), eq("NewSecret123!"), anyString());
        assertTrue(session.isInvalid());
    }

    @Test void currentPasswordRestrictionsReturn429WithoutAdvertisingManualProtectionExpiry() throws Exception {
        when(profiles.changePassword(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new LoginProtectionService.Outcome(null, 900), new LoginProtectionService.Outcome(null, 0, true));
        for (int attempt = 0; attempt < 2; attempt++) {
            var action = mvc.perform(post("/my-page/password").with(signedIn()).with(csrf()).header("X-Requested-With", "XMLHttpRequest")
                    .param("currentPassword", "bad").param("newPassword", "NewSecret123!").param("passwordConfirmation", "NewSecret123!"))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.success").value(false));
            if (attempt == 0) action.andExpect(header().string("Retry-After", "900"));
            else action.andExpect(header().doesNotExist("Retry-After"));
        }
    }

    @Test void failedLoginReturnsNgAndCreatesNoAuthentication() throws Exception {
        var result = mvc.perform(post("/users/login").with(csrf()).param("userName", "admin").param("password", "wrong"))
            .andExpect(status().isOk()).andExpect(content().string("NG")).andReturn();
        assertNull(result.getRequest().getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }
    @Test void protectedOrReleasedOldSessionIsRejectedBeforeBusinessController() throws Exception {
        var session=new MockHttpSession();sessions.start(session,admin,0);
        var auth=UsernamePasswordAuthenticationToken.authenticated(new PortalPrincipal(1L,"admin",0,0),null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new org.springframework.security.core.context.SecurityContextImpl(auth));
        when(accountProtection.valid(1L,0)).thenReturn(false);
        mvc.perform(post("/notices/save").session(session).with(csrf()).header("X-Requested-With","XMLHttpRequest"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_PROTECTED"));
        assertTrue(session.isInvalid());verify(notices,never()).save(any(),any());
    }

    @Test void fakeLegacyAttributesDoNotAuthenticateAndUnknownRoleIsDenied() throws Exception {
        var session = new MockHttpSession(); session.setAttribute("loginUserId", 1L); session.setAttribute("loginRole", "ADMIN");
        mvc.perform(get("/users/new").session(session).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isUnauthorized());
        assertNull(session.getAttribute("loginUserId"));
        admin.setRole("UNRECOGNIZED");
        mvc.perform(get("/users/new").with(signedIn()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isUnauthorized());
    }

    @Test void validTokenStillRequiresRoleAndFreshAccountStatus() throws Exception {
        admin.setRole("EMPLOYEE");
        mvc.perform(post("/users/2/deactivate").with(signedIn()).with(csrf()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        admin.setStatus(0);
        mvc.perform(get("/users/new").with(signedIn()).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isUnauthorized());
        verify(users, never()).deactivate(any());
    }

    @Test void logoutRequiresPostAndValidTokenAndClearsSession() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/users/new").session(session).with(signedIn())).andExpect(status().isOk());
        mvc.perform(get("/logout").session(session).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isMethodNotAllowed());
        assertFalse(session.isInvalid());
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        assertFalse(session.isInvalid());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true)).andExpect(cookie().maxAge("JSESSIONID", 0));
        assertTrue(session.isInvalid());
    }

    @Test void tokenFromAnotherSessionAndOldNoticeHeaderAreRejected() throws Exception {
        var first = mvc.perform(get("/")).andReturn();
        var token = (org.springframework.security.web.csrf.CsrfToken) first.getRequest().getAttribute("_csrf");
        var other = new MockHttpSession();
        mvc.perform(post("/users/login").session(other).header("X-CSRF-TOKEN", token.getToken())
                .param("userName", "admin").param("password", "123"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/notices/2/read").with(signedIn()).header("X-Notice-CSRF", "old-token"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test void manuallyProtectedLoginDoesNotAdvertiseAnAutomaticUnlockOrAuthenticate() throws Exception {
        when(protection.login(anyString(),anyString(),any())).thenReturn(new LoginProtectionService.Outcome(null,0,true));
        var response=mvc.perform(post("/users/login").with(csrf()).param("userName","admin").param("password","123"))
            .andExpect(status().isTooManyRequests()).andExpect(header().doesNotExist("Retry-After")).andReturn();
        assertNull(response.getRequest().getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    private MockHttpSession loggedInSession() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/users/new").session(session).with(signedIn())).andExpect(status().isOk());
        return session;
    }
    @Test void idleAndAbsoluteExpiryBlockWritesBeforeBusinessAndBackgroundDoesNotExtendLifetime() throws Exception {
        for (String name : List.of(SessionLifecycle.ACTIVE, SessionLifecycle.ISSUED)) {
            var session = loggedInSession();
            session.setAttribute(name, System.currentTimeMillis() - (name.equals(SessionLifecycle.ACTIVE) ? 1800001L : 28800001L));
            mvc.perform(post("/users/2/deactivate").session(session).with(csrf()).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(name.equals(SessionLifecycle.ACTIVE) ? "SESSION_IDLE_EXPIRED" : "SESSION_ABSOLUTE_EXPIRED"));
            assertTrue(session.isInvalid());
        }
        verify(users, never()).deactivate(any());
        var session = loggedInSession(); long prior = System.currentTimeMillis() - 100000L;
        session.setAttribute(SessionLifecycle.ACTIVE, prior);
        mvc.perform(get("/users/new").session(session).header("X-Background-Request", "1")).andExpect(status().isOk());
        assertEquals(prior, session.getAttribute(SessionLifecycle.ACTIVE));
        mvc.perform(post("/session/activity").session(session)).andExpect(status().isForbidden());
        assertEquals(prior, session.getAttribute(SessionLifecycle.ACTIVE));
        mvc.perform(post("/session/activity").session(session).with(csrf())).andExpect(status().isOk());
        assertTrue((Long) session.getAttribute(SessionLifecycle.ACTIVE) > prior);
    }
    @Test void changedGenerationDoesNotSurviveAndAuthenticationOutageIsNotBadPassword() throws Exception {
        var session = loggedInSession(); admin.setAuthenticationVersion(1);
        mvc.perform(get("/users/new").session(session).header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertTrue(session.isInvalid());
        when(protection.login(anyString(), anyString(), any())).thenReturn(new LoginProtectionService.Outcome(null, 900));
        mvc.perform(post("/users/login").with(csrf()).param("userName", "admin").param("password", "wrong"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "900"));
        when(protection.login(anyString(), anyString(), any())).thenThrow(new IllegalStateException("not exposed"));
        mvc.perform(post("/users/login").with(csrf()).param("userName", "admin").param("password", "wrong"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("not exposed"))));
    }
}
