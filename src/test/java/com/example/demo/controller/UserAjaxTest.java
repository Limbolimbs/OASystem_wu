package com.example.demo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Optional;

import com.example.demo.entity.User;
import com.example.demo.security.SystemRole;
import com.example.demo.service.UserService;
import com.example.demo.service.DepartmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
class UserAjaxTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserService users;
    @MockitoBean DepartmentService departments;
    MockHttpSession session;
    User admin;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        session.setAttribute("loginUserId", 1L);
        admin = new User();
        admin.setId(1L);
        admin.setRealName("管理者");
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        when(users.findById(1L)).thenReturn(Optional.of(admin));
    }

    @Test
    void roleUpdateReturnsJsonWithoutRedirect() throws Exception {
        mvc.perform(post("/users/2/role").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("role", "GENERAL_AFFAIRS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(header().doesNotExist("Location"));
        verify(users).updateRole(2L, SystemRole.GENERAL_AFFAIRS);
    }

    @Test
    void selfRoleChangeStillRejected() throws Exception {
        mvc.perform(post("/users/1/role").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("role", "EMPLOYEE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verify(users, never()).updateRole(any(), any());
    }

    @Test
    void employeeCannotUseAdminAjaxEndpoints() throws Exception {
        admin.setRole("EMPLOYEE");
        mvc.perform(post("/users/2/deactivate").session(session).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/users").session(session).param("fragment", "employee-data")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
        verify(users, never()).deactivate(any());
    }

    @Test
    void expiredSessionReturns401NotHtml() throws Exception {
        mvc.perform(post("/users/2/activate").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.redirectUrl").value("/"));
    }

    @Test
    void activationAndDeactivationReturnJson() throws Exception {
        when(users.deactivate(2L)).thenReturn(true);
        when(users.activate(2L)).thenReturn(true);
        mvc.perform(post("/users/2/deactivate").session(session).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/users/2/activate").session(session).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void invalidRoleReturnsJapaneseJsonError() throws Exception {
        mvc.perform(post("/users/2/role").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("role", "SUPERUSER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void duplicateRegistrationReturnsConflictWithoutLosingPage() throws Exception {
        when(users.existsByUsername("tanaka")).thenReturn(true);
        mvc.perform(post("/users/save").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("username", "tanaka").param("password", "Abcdefgh1234").param("passwordConfirmation", "Abcdefgh1234")
                        .param("realName", "田中 太郎").param("role", "EMPLOYEE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.success").value(false))
                .andExpect(header().doesNotExist("Location"));
        verify(users, never()).save(any());
    }

    @Test
    void registrationDoesNotReturnPasswordOrAcceptInjectedIdAndStatus() throws Exception {
        mvc.perform(post("/users/save").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("username", " EMP000123 ").param("password", "Abcdefgh1234").param("passwordConfirmation", "Abcdefgh1234")
                        .param("realName", "田中 太郎").param("role", "EMPLOYEE")
                        .param("id", "999").param("status", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(content().string(not(containsString("Abcdefgh1234"))));
        ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
        verify(users).save(captured.capture());
        org.junit.jupiter.api.Assertions.assertNull(captured.getValue().getId());
        org.junit.jupiter.api.Assertions.assertEquals(1, captured.getValue().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("EMP000123", captured.getValue().getUsername());
    }

    @Test
    void employeeFragmentRendersOnlyRequestedRegionAndDelegatedForms() throws Exception {
        User employee = new User();
        employee.setId(2L);
        employee.setUsername("tanaka");
        employee.setRealName("田中 太郎");
        employee.setRole("EMPLOYEE");
        when(users.findAll()).thenReturn(List.of(admin, employee));
        mvc.perform(get("/users").session(session).param("fragment", "employee-data"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"employee-data\"")))
                .andExpect(content().string(containsString("data-ajax-targets=\"employee-data employee-count\"")))
                .andExpect(content().string(not(containsString("<html"))))
                .andExpect(content().string(not(containsString("<script"))));
        mvc.perform(get("/users").session(session).param("fragment", "employee-count"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"employee-count\"")));
    }

    @Test
    void registrationPageLoadsSharedAjaxScript() throws Exception {
        var result = mvc.perform(get("/users/new").session(session))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/js/page-interactions.js")))
                .andExpect(content().string(containsString("data-ajax-reset")))
                .andExpect(content().string(containsString("/js/message-dialog.js")))
                .andExpect(content().string(containsString("社員番号（ログインID）")))
                .andExpect(content().string(containsString("12～128文字")))
                .andExpect(content().string(containsString("passwordConfirmation"))).andReturn();
        var preview = java.nio.file.Path.of("target", "ui-preview", "user-form.html");
        java.nio.file.Files.createDirectories(preview.getParent());
        java.nio.file.Files.writeString(preview, result.getResponse().getContentAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Aa123456789", "abcdefghijkl", "abcdefgh1234", "ABCDEFGHIJK!", "Aa12345678\n!", "Aa123456789\u0000"})
    void weakPasswordIsRejectedByBackendWithoutAjaxHeader(String password) throws Exception {
        mvc.perform(post("/users/save").session(session).param("username", "EMP000123")
                        .param("password", password).param("passwordConfirmation", password)
                        .param("realName", "田中 太郎").param("role", "EMPLOYEE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        verify(users, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcdefgh1234", "abcdefgh123!", "ABCDEFGH123!", "Abcdefghijk!", "Abcdefg1234!"})
    void anyThreeCharacterCategoriesAreAccepted(String password) throws Exception {
        mvc.perform(post("/users/save").session(session).param("username", "EMP000123")
                        .param("password", password).param("passwordConfirmation", password)
                        .param("realName", "田中 太郎").param("role", "EMPLOYEE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(users).save(any());
    }

    @Test
    void longPasswordAndDifferentConfirmationAreRejected() throws Exception {
        String longPassword = "Aa1" + "x".repeat(126);
        mvc.perform(post("/users/save").session(session).param("username", "EMP000123")
                        .param("password", longPassword).param("passwordConfirmation", longPassword)
                        .param("realName", "田中 太郎").param("role", "EMPLOYEE"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/save").session(session).param("username", "EMP000123")
                        .param("password", "Abcdefgh1234").param("passwordConfirmation", "Different123!")
                        .param("realName", "田中 太郎").param("role", "EMPLOYEE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("パスワードと確認用パスワードが一致しません。"));
        verify(users, never()).save(any());
    }

    @Test
    void missingConfirmationAndOversizedLoginIdAreRejected() throws Exception {
        mvc.perform(post("/users/save").session(session).param("username", "EMP000123")
                        .param("password", "Abcdefgh1234").param("realName", "田中 太郎").param("role", "EMPLOYEE"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/save").session(session).param("username", "E".repeat(51))
                        .param("password", "Abcdefgh1234").param("passwordConfirmation", "Abcdefgh1234")
                        .param("realName", "田中 太郎").param("role", "EMPLOYEE"))
                .andExpect(status().isBadRequest());
        verify(users, never()).save(any());
    }

    @Test
    void departmentAssignmentRequiresAdminAndUsesJson() throws Exception {
        mvc.perform(post("/users/2/department").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("department", "営業部"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(departments).assignEmployee(2L, "営業部");
        admin.setRole("EMPLOYEE");
        mvc.perform(post("/users/3/department").session(session).header("X-Requested-With", "XMLHttpRequest")
                        .param("department", "営業部"))
                .andExpect(status().isForbidden());
        verify(departments, never()).assignEmployee(3L, "営業部");
    }
}
