package com.example.demo.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import com.example.demo.entity.User;
import com.example.demo.notice.*;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.NoticeService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NoticeController.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(addFilters = false)
class NoticeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccessControlService access;
    @MockitoBean NoticeService notices;
    User actor;
    MockHttpSession session;
    @BeforeEach void setup() {
        actor = new User(); actor.setId(1L); actor.setRole("ADMIN"); actor.setRealName("管理者");
        session = new MockHttpSession(); session.setAttribute("loginUserId",1L); session.setAttribute("noticeCsrf","test-token");
        when(access.currentUser(any())).thenReturn(Optional.of(actor)); when(access.isAdmin(actor)).thenReturn(true);
        when(notices.canEdit(actor)).thenReturn(true);
        var item = new NoticeService.Summary(1L,2L,"社内のお知らせ","一般",NoticePriority.IMPORTANT,NoticeStatus.PUBLISHED,
            "管理者",LocalDateTime.of(2026,10,9,12,0),LocalDateTime.of(2026,10,9,12,0),false,false);
        when(notices.list(eq(actor),anyString(),anyString(),anyInt())).thenReturn(new PageImpl<>(List.of(item),PageRequest.of(0,20),1));
    }
    @Test void pageHasJapaneseSeparateResourcesAndAjaxForms() throws Exception {
        var html = mvc.perform(get("/notices").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("/css/notices.css"))).andExpect(content().string(containsString("/js/notices.js")))
            .andExpect(content().string(containsString("https://code.jquery.com/jquery-3.7.1.min.js")))
            .andExpect(content().string(containsString("data-ajax-targets=\"notice-data notice-count\"")))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Path dir = Path.of("D:/oa-maintenance/notice-20261009"); Files.createDirectories(dir); Files.writeString(dir.resolve("admin-preview.html"),html);
    }
    @Test void employeeDoesNotGetEditorOrManagementActions() throws Exception {
        when(access.isAdmin(actor)).thenReturn(false); when(notices.canEdit(actor)).thenReturn(false);
        mvc.perform(get("/notices").session(session)).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("id=\"noticeForm\""))))
            .andExpect(content().string(not(containsString("/notices/1/deactivate"))));
    }
    @Test void fragmentDoesNotReturnWholePage() throws Exception {
        mvc.perform(get("/notices").param("fragment","notice-data").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"notice-data\"")))
            .andExpect(content().string(not(containsString("<html"))));
        mvc.perform(get("/notices").param("fragment","bogus").header("X-Requested-With","XMLHttpRequest").session(session)).andExpect(status().isBadRequest());
    }
    @Test void unauthenticatedPageRedirectsButAjaxReturns401() throws Exception {
        when(access.currentUser(any())).thenReturn(Optional.empty());
        mvc.perform(get("/notices")).andExpect(redirectedUrl("/"));
        mvc.perform(get("/notices").param("fragment","notice-data").header("X-Requested-With","XMLHttpRequest")).andExpect(status().isUnauthorized());
    }
    @Test void savesOnlyFormFields() throws Exception {
        mvc.perform(post("/notices/save").session(session).header("X-Requested-With","XMLHttpRequest").header("X-Notice-CSRF","test-token")
            .param("title","件名").param("content","本文").param("category","一般").param("priority","NORMAL").param("status","DRAFT"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(notices).save(eq(actor),argThat(f -> f.id()==null && f.title().equals("件名") && f.status()==NoticeStatus.DRAFT));
    }
    @Test void readAndDeactivateUseVersionAndAuthenticatedActor() throws Exception {
        for(String operation : List.of("read","deactivate","restore")) mvc.perform(post("/notices/1/"+operation).session(session)
            .header("X-Requested-With","XMLHttpRequest").header("X-Notice-CSRF","test-token").param("version","2"))
            .andExpect(status().isOk());
        verify(notices).markRead(actor,1L,2L); verify(notices).deactivate(actor,1L,2L); verify(notices).restore(actor,1L,2L);
    }
}
