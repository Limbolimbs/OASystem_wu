package com.example.control;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@WebMvcTest(SessionController.class)
@Import(ConsoleSecurity.class)
class SessionPageTest {
 @Autowired org.springframework.test.web.servlet.MockMvc mvc;
 @MockitoBean ConsoleSessions sessions;@MockitoBean ConsoleStore store;@MockitoBean ConsoleMembers members;@MockitoBean ConsoleProvider provider;
 @MockitoBean Clock clock;@MockitoBean OperatorSettings settings;@MockitoBean(name="readiness")org.springframework.boot.ApplicationRunner readiness;
 @BeforeEach void prepare(){when(clock.millis()).thenAnswer(call->System.currentTimeMillis());when(store.valid(any())).thenReturn(true);when(store.sessionValid(any(),any())).thenReturn(true);}
 MockHttpSession session(String role){var session=new MockHttpSession();session.setAttribute("issued",System.currentTimeMillis());session.setAttribute("active",System.currentTimeMillis());var auth=UsernamePasswordAuthenticationToken.authenticated(new ConsoleIdentity("00000000-0000-4000-8000-000000000001","test",role,0),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new org.springframework.security.core.context.SecurityContextImpl(auth));return session;}
 @ParameterizedTest @ValueSource(strings={"OWNER","OPERATOR","OBSERVER"})void allManagementRolesCanReadScreenAndList(String role)throws Exception{mvc.perform(get("/sessions").session(session(role))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("セッション管理"))).andExpect(content().string(org.hamcrest.Matchers.containsString("/js/sessions.js")));when(sessions.list("ALL","ACTIVE","","",0)).thenReturn(Map.of("items",List.of(),"total",0));mvc.perform(get("/api/sessions").session(session(role))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));}
 @ParameterizedTest @ValueSource(strings={"revoke","revoke-all","request","request-all"})void writesCannotSkipCsrf(String action)throws Exception{mvc.perform(post("/api/sessions/00000000-0000-4000-8000-000000000002/"+action).session(session("OWNER")).param("version","0").param("reason","確認").param("otp","123456")).andExpect(status().isForbidden());verifyNoInteractions(sessions);}
 @Test void terminatedRegistrySessionIsRejectedBeforeWrite()throws Exception{when(store.sessionValid(any(),any())).thenReturn(false);var previous=session("OWNER");mvc.perform(post("/api/sessions/00000000-0000-4000-8000-000000000002/revoke").session(previous).with(csrf()).param("version","0").param("reason","確認").param("otp","123456")).andExpect(status().isUnauthorized());org.junit.jupiter.api.Assertions.assertTrue(previous.isInvalid());verifyNoInteractions(sessions);}
}
