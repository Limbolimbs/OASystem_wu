package com.example.demo.config;

import com.example.demo.security.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LoginInterceptorTest {

    private LoginInterceptor interceptor;

    @BeforeEach
    void setUp() {
        AuditService auditService = mock(AuditService.class);
        interceptor = new LoginInterceptor(auditService);
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("loginUserId", 1L);
        session.setAttribute("loginUser", "EMP0001");
        session.setAttribute("loginRole", role);
        return session;
    }

    @Test
    void missingSessionRedirectsPageToLoginWithoutCreatingSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/attendance");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals("/", response.getRedirectedUrl());
        assertEquals(null, request.getSession(false));
    }

    @Test
    void anonymousAjaxGets401RatherThanLoginHtml() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/notices");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_REQUIRED"));
    }

    @Test
    void anonymousApiGets401WithoutAjaxHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void employeeCanOpenOwnBusinessPagesButNotAdministratorPages() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/attendance");
        request.setSession(session("EMPLOYEE"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        request.setRequestURI("/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void administratorCanOpenEmployeeManagement() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/new");
        request.setSession(session("ADMIN"));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void contextPathDoesNotBypassAdministratorCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oa/departments/2");
        request.setContextPath("/oa");
        request.setSession(session("GENERAL_AFFAIRS"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }
}
