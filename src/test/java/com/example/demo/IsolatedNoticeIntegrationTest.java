package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.example.demo.entity.User;
import com.example.demo.notice.*;
import com.example.demo.repository.*;
import com.example.demo.service.NoticeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** 隔離公告・既読のみを更新し、各ケース開始前に識別済みfixtureへ戻す。 */
@EnabledIfEnvironmentVariable(named = "OA_ACCEPTANCE_TEST", matches = "true")
class IsolatedNoticeIntegrationTest extends com.example.demo.acceptance.IsolatedIntegrationSupport {
    @Autowired NoticeService service;
    @Autowired NoticeRepository notices;
    @Autowired NoticeReadRepository reads;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.example.demo.security.SessionLifecycle sessionLifecycle;

    MockHttpSession session(User actor) {
        var s = new MockHttpSession(); s.setAttribute("loginUserId",actor.getId());
        var authentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
            new com.example.demo.security.PortalPrincipal(actor.getId(), actor.getUsername()), null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + actor.getRole())));
        s.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new org.springframework.security.core.context.SecurityContextImpl(authentication)); sessionLifecycle.start(s, actor); return s;
    }
    @Test void publicationReadEditAndLogicalDeleteWorkAgainstDatabase() throws Exception {
        assertEquals(com.example.demo.acceptance.IsolatedDatabase.NAME,jdbc.queryForObject("SELECT DATABASE()",String.class));
        var people=users.findByStatusOrderByIdAsc(1);
        User admin=people.stream().filter(u->u.getRole().equals("ADMIN")).findFirst().orElseThrow();
        User employee=people.stream().filter(u->u.getRole().equals("EMPLOYEE")).findFirst().orElseThrow();
        String title="OA_NOTICE_VERIFY_"+UUID.randomUUID();
        long before=service.unreadCount(employee);
        service.save(admin,new NoticeForm(null,null,title,"本文\n2行目","一般",NoticePriority.IMPORTANT,NoticeStatus.DRAFT));
        Long id=jdbc.queryForObject("SELECT id FROM notice WHERE title=?",Long.class,title);
        assertTrue(service.list(employee,title,"ALL",0).isEmpty());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.detail(employee,id));
        var draft=service.detail(admin,id);
        service.save(admin,new NoticeForm(id,draft.version(),title,"公開本文","一般",NoticePriority.IMPORTANT,NoticeStatus.PUBLISHED));
        var published=service.detail(employee,id);
        assertEquals(before+1,service.unreadCount(employee));
        service.markRead(employee,id,published.version());
        assertEquals(before,service.unreadCount(employee));
        assertTrue(service.list(employee,title,"ALL",0).getContent().getFirst().read());
        mvc.perform(get("/notices").session(session(employee))).andExpect(status().isOk());
        mvc.perform(post("/notices/save").session(session(employee)).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).header("X-Requested-With","XMLHttpRequest")
            .param("title",title).param("content","本文")
            .param("category","一般").param("priority","NORMAL").param("status","DRAFT")).andExpect(status().isForbidden());
        service.save(admin,new NoticeForm(id,published.version(),title,"更新版","一般",NoticePriority.NORMAL,NoticeStatus.PUBLISHED));
        assertEquals(before+1,service.unreadCount(employee));
        var edited=service.detail(admin,id);
        service.deactivate(admin,id,edited.version());
        assertTrue(service.list(employee,title,"ALL",0).isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT delete_flag FROM notice WHERE id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notice_read WHERE notice_id=?",Integer.class,id));
        var inactive=service.detail(admin,id);
        service.restore(admin,id,inactive.version());
        assertEquals(NoticeStatus.DRAFT,service.detail(admin,id).status());
        assertTrue(service.list(employee,title,"ALL",0).isEmpty());
    }
}
