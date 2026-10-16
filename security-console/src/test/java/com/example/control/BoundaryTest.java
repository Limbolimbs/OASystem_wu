package com.example.control;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.util.*;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@WebMvcTest({ConsoleController.class,MemberController.class})
@Import({ConsoleSecurity.class,ConsoleProvider.class,BoundaryTest.Config.class})
class BoundaryTest {
 static final String ID="970ce5f2-6609-4d43-8b41-87d60f23db50";
 @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry r){
  r.add("server.ssl.enabled",()->"false");r.add("control.operator-id",()->ID);r.add("control.login-id",()->"test_operator");r.add("control.password-hash",()->OperatorCrypto.encoder().encode("TestPassword123!"));r.add("control.totp-secret",()->"GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
 }
 @TestConfiguration static class Config {
  @Bean OperatorSettings settings(){var s=new OperatorSettings();s.setOperatorId(ID);s.setLoginId("test_operator");s.setPasswordHash(OperatorCrypto.encoder().encode("TestPassword123!"));s.setTotpSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");return s;}
 }
 @Autowired MockMvc mvc;
 @MockitoBean ConsoleStore store;
 @MockitoBean ConsoleEvidence evidence;
 @MockitoBean ConsoleMembers members;
 @MockitoBean JdbcTemplate jdbc;
 @MockitoBean(name="readiness") org.springframework.boot.ApplicationRunner readiness;
 @BeforeEach void prepare(){when(store.active()).thenReturn(true);when(store.valid(any())).thenReturn(true);when(store.sessionValid(any(),any())).thenReturn(true);}
 MockHttpSession session(){var session=new MockHttpSession();session.setAttribute("issued",System.currentTimeMillis());session.setAttribute("active",System.currentTimeMillis());var auth=UsernamePasswordAuthenticationToken.authenticated(new ConsoleIdentity(ID,"test_operator"),null,List.of(new SimpleGrantedAuthority("ROLE_OWNER")));session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new org.springframework.security.core.context.SecurityContextImpl(auth));return session;}
 @Test void publicPageIsNeutralAndProtectsResources()throws Exception{
  mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("管理アクセス"))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("OASystem")))).andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
  mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
  mvc.perform(get("/").with(r->{r.setRemoteAddr("203.0.113.20");return r;})).andExpect(status().isForbidden());
 }
 @ParameterizedTest @ValueSource(strings={"/api/accounts/1/protect","/api/accounts/1/release","/api/sources/abcdef/restrict","/api/sources/abcdef/release","/auth/logout","/auth/login","/api/activity"})
 void everyWriteRequiresCsrf(String path)throws Exception{mvc.perform(post(path).session(session())).andExpect(status().isForbidden());}
 @Test void oaAuthenticationCannotBecomeOperator()throws Exception{
  mvc.perform(get("/console").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/accounts").with(user("admin").roles("OPERATOR"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/evidence/"+ID)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/evidence/"+ID).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
  verifyNoInteractions(evidence);
 }
 @Test void otpAndPasswordProviderAreRequiredAndLoginRotatesSession()throws Exception{
  when(store.login("test_operator","TestPassword123!","123456")).thenReturn(new ConsoleStore.LoginResult(true,0));
  var initial=mvc.perform(get("/")).andReturn();var session=(MockHttpSession)initial.getRequest().getSession();String prior=session.getId();
  mvc.perform(post("/auth/login").session(session).with(csrf()).param("operator","test_operator").param("password","TestPassword123!").param("otp","123456")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
  assertNotEquals(prior,session.getId());verify(store).login("test_operator","TestPassword123!","123456");
  assertNotNull(session.getAttribute("issued"));
 }
 @Test void idleAndAbsoluteExpiryRejectBeforeWrites()throws Exception{
  for(String field:List.of("active","issued")){var session=session();session.setAttribute(field,System.currentTimeMillis()-(field.equals("active")?600001L:3600001L));mvc.perform(post("/api/accounts/1/protect").session(session).with(csrf())).andExpect(status().isUnauthorized());assertTrue(session.isInvalid());}
  verify(store,never()).account(anyLong(),anyBoolean(),any(),any());
 }
 @Test void automaticReadDoesNotExtendSessionAndUnknownRoutesAreDenied()throws Exception{
  var session=session();long prior=System.currentTimeMillis()-60000;session.setAttribute("active",prior);when(store.overview()).thenReturn(Map.of("limited",0));
  mvc.perform(get("/api/overview").session(session)).andExpect(status().isOk());assertEquals(prior,session.getAttribute("active"));
  mvc.perform(get("/unknown").session(session)).andExpect(status().isForbidden());
 }
 @Test void memberAdminApiRejectsOperatorsAndEveryEnrollmentWriteNeedsCsrf()throws Exception{
  var session=session();var identity=new ConsoleIdentity("7b16a9c1-aeae-4a30-b16b-c1d55d34d7cb","staff","OPERATOR",0);session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new org.springframework.security.core.context.SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(identity,null,List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")))));
  mvc.perform(get("/api/members").session(session)).andExpect(status().isForbidden());mvc.perform(post("/api/members").session(session).with(csrf())).andExpect(status().isForbidden());verify(members,never()).list();
  for(String path:List.of("/enrollment/start","/enrollment/confirm","/api/reviews","/api/personal/password"))mvc.perform(post(path)).andExpect(status().isForbidden());
 }
 @Test void revokedMemberSessionIsRejectedBeforeAnyRead()throws Exception{when(store.valid(any())).thenReturn(false);var session=session();mvc.perform(get("/api/overview").session(session)).andExpect(status().isUnauthorized());assertTrue(session.isInvalid());verify(store,never()).overview();}
 @Test void protectedMemberCannotUsePreviouslyIssuedSessionToWrite()throws Exception{
  when(store.valid(any())).thenReturn(false);var session=session();
  mvc.perform(post("/api/accounts/1/protect").session(session).with(csrf()).param("reason","本人確認").param("otp","123456")).andExpect(status().isUnauthorized());
  assertTrue(session.isInvalid());verify(store,never()).account(anyLong(),anyBoolean(),any(),any());
 }
 @ParameterizedTest @ValueSource(strings={"OWNER","OPERATOR","OBSERVER"})
 void consoleShortcutsRemainVisibleWithoutGrantingMemberManagement(String role)throws Exception{
  var session=session();var identity=new ConsoleIdentity(ID,"test_operator",role,0);
  session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new org.springframework.security.core.context.SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(identity,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)))));
  var html=mvc.perform(get("/console").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertTrue(html.contains("管理ショートカット"));assertTrue(html.contains("id=\"personalShortcutLabel\""));assertTrue(html.contains("現在の権限"));
  assertEquals(role.equals("OWNER"),html.contains("id=\"membersShortcutLabel\""));
  assertEquals(role.equals("OWNER"),html.contains("href=\"/members\""));
  assertTrue(html.contains("class=\"rail-tool-link\" href=\"/personal\""));
 }
}
