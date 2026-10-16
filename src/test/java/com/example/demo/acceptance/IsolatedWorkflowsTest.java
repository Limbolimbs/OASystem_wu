package com.example.demo.acceptance;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.example.demo.attendance.AttendanceClock;
import com.example.demo.security.PasswordHashes;

/** 実フィルター・実サービス・専用MySQL。認証主体の注入や業務モックは使用しない。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="OA_ACCEPTANCE_TEST",matches="true")
class IsolatedWorkflowsTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired com.example.demo.security.LoginProtectionService loginProtection;
    @Autowired com.example.demo.service.UserService users;
    @Autowired com.example.demo.service.SalesService salesService;
    @Autowired com.example.demo.service.DepartmentService departments;
    @Autowired com.example.demo.service.AttendancePolicyService policies;
    @Autowired com.example.demo.service.AttendanceDayService days;
    @Autowired com.example.demo.repository.UserRepository userRepository;
    @Autowired com.example.demo.security.AuditService audit;
    @Autowired com.example.demo.security.SecuritySettings securitySettings;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        try(var connection=IsolatedDatabase.connection()) { IsolatedDatabase.require(connection); }
        Path key=Path.of("D:/oa-maintenance/business-acceptance-20261015/qa-public-3072.pem");
        if(!Files.exists(key)){
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var pair=generator.generateKeyPair();
            Files.writeString(key,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n",StandardCharsets.US_ASCII);
        }
        r.add("spring.datasource.url",()->IsolatedDatabase.URL);r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->IsolatedDatabase.PASSWORD);
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.show-sql",()->"false");r.add("spring.jpa.open-in-view",()->"false");
        r.add("spring.datasource.hikari.maximum-pool-size",()->"6");r.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone='+09:00'");
        r.add("oa.security.evidence-public-key",()->key.toUri().toString());r.add("oa.security.session-node",()->"isolated-qa-acceptance");
        r.add("server.address",()->"127.0.0.1");r.add("spring.sql.init.mode",()->"never");
    }
    @BeforeEach void fixture() throws Exception { IsolatedFixtures.reset(source); }
    MockHttpServletRequestBuilder ajax(MockHttpServletRequestBuilder request){return request.header("X-Requested-With","XMLHttpRequest").with(r->{r.setRemoteAddr("192.0.2.55");return r;});}
    MockHttpSession login(int actor) throws Exception {
        var initial=mvc.perform(ajax(get("/"))).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();String old=session.getId();
        var logged=mvc.perform(ajax(post("/users/login")).session(session).with(csrf()).param("userName","QA_EMP_"+actor).param("password",IsolatedDatabase.ACCOUNT_PASSWORD)).andReturn();
        if(logged.getResponse().getStatus()==503)loginProtection.login("QA_EMP_"+actor,IsolatedDatabase.ACCOUNT_PASSWORD,"192.0.2.55");
        assertEquals(200,logged.getResponse().getStatus());assertEquals("OK",logged.getResponse().getContentAsString());
        assertNotEquals(old,logged.getRequest().getSession().getId());return (MockHttpSession)logged.getRequest().getSession();
    }
    ResultActions read(MockHttpSession session,String url) throws Exception {return mvc.perform(ajax(get(url)).session(session));}
    ResultActions write(MockHttpSession session,String url,String... pairs) throws Exception {
        var request=ajax(post(url)).session(session).with(csrf());
        for(int i=0;i<pairs.length;i+=2)request.param(pairs[i],pairs[i+1]);
        return mvc.perform(request);
    }
    long count(String sql,Object... parameters){return jdbc.queryForObject(sql,Long.class,parameters);}
    long latest(String table){return jdbc.queryForObject("SELECT MAX(id) FROM "+table,Long.class);}
    LocalDate workday(int offset){LocalDate day=AttendanceClock.today().plusDays(offset);while(day.getDayOfWeek().getValue()>5)day=day.plusDays(1);return day;}
    void submit(MockHttpSession session,String type,LocalDate day,String... extra) throws Exception {
        var values=new ArrayList<>(List.of("type",type,"startDate",day.toString(),"endDate",day.toString(),"reason","隔離試験の申請"));values.addAll(List.of(extra));
        write(session,"/attendance/manage/requests",values.toArray(String[]::new)).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5})
    void roleMatrixForPagesApiFragmentsAndHomeRouting(int actor) throws Exception {
        var session=login(actor);
        for(String url:List.of("/my-page","/attendance","/attendance/history","/attendance/manage","/notices","/attendance?fragment=attendance-records","/notices?fragment=notice-data"))read(session,url).andExpect(status().isOk());
        for(String url:List.of("/users","/users/new","/departments","/api/dashboard","/users?fragment=employee-data"))read(session,url).andExpect(status().is(actor==1?200:403));
        read(session,"/sales").andExpect(status().is(actor==1||actor==4||actor==5?200:403));
        var home=read(session,"/home").andExpect(status().is(actor==1?200:302)).andReturn();
        if(actor!=1)assertEquals(actor==4||actor==5?"/sales":"/attendance",home.getResponse().getRedirectedUrl());
        read(session,"/attendance/manage?fragment=review").andExpect(status().is(actor<=2?200:403));
        for(String fragment:List.of("rules","holiday-list","schedule-list"))read(session,"/attendance/manage?fragment="+fragment).andExpect(status().is(actor==1?200:403));
        String html=read(session,"/my-page").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(actor==1,html.contains("href=\"/departments\""));assertEquals(actor==1||actor==4||actor==5,html.contains("href=\"/sales\""));
        assertFalse(html.contains(jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?",String.class,91000+actor)));
    }
    @ParameterizedTest @ValueSource(ints={2,3,4,5})
    void forbiddenAdministrativeWritesHaveNoEffect(int actor) throws Exception {
        var session=login(actor);String password=jdbc.queryForObject("SELECT password FROM sys_user WHERE id=91003",String.class);
        for(String path:List.of("/users/save","/users/91003/role","/users/91003/deactivate","/users/91006/activate","/users/91003/department","/departments","/departments/92001"))write(session,path).andExpect(status().isForbidden());
        write(session,"/attendance/manage/policy","standardStart","09:00","standardEnd","18:00","breakMinutes","60","lateGraceMinutes","0","earlyGraceMinutes","0","overtimeThresholdMinutes","0").andExpect(status().isForbidden());
        assertEquals(1,count("SELECT status FROM sys_user WHERE id=91003"));assertEquals(password,jdbc.queryForObject("SELECT password FROM sys_user WHERE id=91003",String.class));
    }
    @Test void anonymousCsrfDisabledAndUnknownIdentityAreRejected() throws Exception {
        for(String path:List.of("/users/login","/logout","/my-page/password","/sales/save","/notices/save","/attendance/clock-in","/attendance/manage/requests"))mvc.perform(ajax(post(path))).andExpect(status().isForbidden());
        mvc.perform(ajax(get("/my-page"))).andExpect(status().isUnauthorized());
        mvc.perform(ajax(post("/users/login")).with(csrf()).param("userName","QA_EMP_6").param("password",IsolatedDatabase.ACCOUNT_PASSWORD)).andExpect(status().isOk()).andExpect(content().string("NG"));
        var session=login(3);read(session,"/unknown-qa-route").andExpect(status().isForbidden());
        write(session,"/attendance/clock-in").andExpect(status().isOk());
        mvc.perform(ajax(post("/attendance/clock-out")).session(session)).andExpect(status().isForbidden());
        assertEquals(1,count("SELECT COUNT(*) FROM attendance_record WHERE clock_out_at IS NULL"));
    }
    @Test void employeeRegistrationValidationMassAssignmentAndDuplicateId() throws Exception {
        var admin=login(1);
        write(admin,"/users/save","username","QA_NEW","password","short","passwordConfirmation","short","realName","新社員","role","EMPLOYEE").andExpect(status().isBadRequest());
        write(admin,"/users/save","username","QA_NEW","password",IsolatedDatabase.ACCOUNT_PASSWORD,"passwordConfirmation",IsolatedDatabase.ACCOUNT_PASSWORD,"realName","新社員","role","EMPLOYEE","department","QA開発部","id","91001","status","0","authenticationVersion","88")
                .andExpect(status().isOk());
        assertEquals(1,count("SELECT status FROM sys_user WHERE username='QA_NEW'"));assertEquals(0,count("SELECT authentication_version FROM sys_user WHERE username='QA_NEW'"));
        assertEquals("QA_EMP_1",jdbc.queryForObject("SELECT username FROM sys_user WHERE id=91001",String.class));
        assertTrue(PasswordHashes.isSupported(jdbc.queryForObject("SELECT password FROM sys_user WHERE username='QA_NEW'",String.class)));
        write(admin,"/users/save","username","QA_NEW","password",IsolatedDatabase.ACCOUNT_PASSWORD,"passwordConfirmation",IsolatedDatabase.ACCOUNT_PASSWORD,"realName","重複","role","EMPLOYEE").andExpect(status().isConflict());
    }
    @Test void logicalDisableRestoreAndRoleChangeInvalidateOldSessions() throws Exception {
        var admin=login(1);var employee=login(3);
        write(admin,"/users/91003/deactivate").andExpect(status().isOk());
        assertEquals(1,count("SELECT delete_flag FROM sys_user WHERE id=91003"));assertEquals(1,count("SELECT COUNT(*) FROM sys_user WHERE id=91003"));
        read(employee,"/my-page").andExpect(status().isUnauthorized());
        write(admin,"/users/91003/activate").andExpect(status().isOk());employee=login(3);
        write(admin,"/users/91003/role","role","GENERAL_AFFAIRS").andExpect(status().isOk());
        read(employee,"/my-page").andExpect(status().isUnauthorized());assertEquals("GENERAL_AFFAIRS",jdbc.queryForObject("SELECT role FROM sys_user WHERE id=91003",String.class));
        write(admin,"/users/91001/deactivate").andExpect(status().isBadRequest());write(admin,"/users/91001/role","role","EMPLOYEE").andExpect(status().isBadRequest());
    }
    @Test void departmentLifecycleAndActiveEmployeeGuard() throws Exception {
        var admin=login(1);
        write(admin,"/departments","code","QA_EMPTY","name","QA空部署","salesEnabled","true").andExpect(status().isOk());long id=latest("department_master");
        write(admin,"/departments/"+id,"version","0","status","0","salesEnabled","false").andExpect(status().isOk());assertEquals(1,count("SELECT delete_flag FROM department_master WHERE id=?",id));
        write(admin,"/departments/"+id,"version","1","status","1","salesEnabled","true").andExpect(status().isOk());
        write(admin,"/departments/92002","version","0","status","0","salesEnabled","false").andExpect(status().isBadRequest());
        write(admin,"/departments/"+id,"version","0","status","1","salesEnabled","false").andExpect(status().isConflict());
    }
    @Test void employeeReactivationCannotRestoreMembershipOfInactiveDepartment() throws Exception {
        var admin=login(1);
        long departmentId=jdbc.queryForObject("SELECT id FROM department_master WHERE name='QA開発部'",Long.class);
        write(admin,"/users/91003/deactivate").andExpect(status().isOk());
        write(admin,"/departments/"+departmentId,"version","0","status","0","salesEnabled","false").andExpect(status().isOk());
        long generation=count("SELECT authentication_version FROM sys_user WHERE id=91003");
        write(admin,"/users/91003/activate").andExpect(status().isConflict());
        assertEquals(0,count("SELECT status FROM sys_user WHERE id=91003"));
        assertEquals(1,count("SELECT delete_flag FROM sys_user WHERE id=91003"));
        assertEquals(generation,count("SELECT authentication_version FROM sys_user WHERE id=91003"));
        write(admin,"/users/91003/department","department","QA総務部").andExpect(status().isOk());
        write(admin,"/users/91003/activate").andExpect(status().isOk());
        read(login(3),"/my-page").andExpect(status().isOk());
        assertEquals("QA総務部",jdbc.queryForObject("SELECT department FROM sys_user WHERE id=91003",String.class));
    }

    @Test void employeeCanReactivateAfterOriginalDepartmentIsRestored() throws Exception {
        var admin=login(1);
        long departmentId=jdbc.queryForObject("SELECT id FROM department_master WHERE name='QA開発部'",Long.class);
        write(admin,"/users/91003/deactivate").andExpect(status().isOk());
        write(admin,"/departments/"+departmentId,"version","0","status","0","salesEnabled","false").andExpect(status().isOk());
        write(admin,"/departments/"+departmentId,"version","1","status","1","salesEnabled","false").andExpect(status().isOk());
        write(admin,"/users/91003/activate").andExpect(status().isOk());
        read(login(3),"/attendance").andExpect(status().isOk());
    }

    @Test void salesOwnDepartmentStaleVersionAndDashboardSynchronization() throws Exception {
        var sales=login(4);var other=login(5);var admin=login(1);
        write(sales,"/sales/save","salesMonth","2026-10","department","QA営業一部","salesAmount","12345.67","orderCount","12","customerCount","4","confirmed","true").andExpect(status().isOk());long id=latest("sales_data");
        read(other,"/sales/"+id).andExpect(status().isForbidden());
        write(other,"/sales/save","salesMonth","2026-10","department","QA営業一部","salesAmount","1","orderCount","1","customerCount","1","confirmed","true").andExpect(status().isForbidden());
        write(sales,"/sales/save","id",Long.toString(id),"version","0","salesMonth","2026-10","department","QA営業一部","salesAmount","20000","orderCount","20","customerCount","5","confirmed","true").andExpect(status().isOk());
        write(sales,"/sales/save","id",Long.toString(id),"version","0","salesMonth","2026-10","department","QA営業一部","salesAmount","9","orderCount","9","customerCount","9","confirmed","true").andExpect(status().isConflict());
        read(admin,"/api/dashboard").andExpect(jsonPath("$.summary.totalSales").value(20000)).andExpect(jsonPath("$.summary.totalOrders").value(20)).andExpect(jsonPath("$.summary.totalCustomers").value(5));
    }
    @Test void punchBreakClockOutSequenceAndOwnHistoryScope() throws Exception {
        var employee=login(3);var ga=login(2);
        write(employee,"/attendance/clock-out").andExpect(status().isConflict());
        write(employee,"/attendance/clock-in").andExpect(status().isOk());write(employee,"/attendance/clock-in").andExpect(status().isConflict());
        write(employee,"/attendance/break-start").andExpect(status().isOk());write(employee,"/attendance/break-start").andExpect(status().isConflict());
        write(employee,"/attendance/clock-out").andExpect(status().isConflict());write(employee,"/attendance/break-end").andExpect(status().isOk());write(employee,"/attendance/break-end").andExpect(status().isConflict());
        write(employee,"/attendance/clock-out").andExpect(status().isOk());assertEquals(4,count("SELECT COUNT(*) FROM attendance_event"));
        read(employee,"/attendance/history?userId=91004").andExpect(status().isForbidden());read(ga,"/attendance/history?userId=91003").andExpect(status().isOk());
        read(employee,"/attendance/history?from=2026-01-01&to=2026-12-31").andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings={"FIELD","REMOTE","LEAVE","HOLIDAY_WORK","OVERTIME","CORRECTION"})
    void sixRequestTypesAreApprovedByGeneralAffairsAndReflected(String type) throws Exception {
        var employee=login(3);var reviewer=login(2);LocalDate day=workday(3);
        if(type.equals("HOLIDAY_WORK")){
            day=AttendanceClock.today().plusDays(1);while(day.getDayOfWeek()!=DayOfWeek.SUNDAY)day=day.plusDays(1);
            write(login(1),"/attendance/manage/holidays","date",day.toString(),"name","隔離試験休日").andExpect(status().isOk());
        }
        String[] extra=type.equals("OVERTIME")?new String[]{"overtimeEndAt",day+"T20:00"}:type.equals("CORRECTION")?new String[]{"clockInAt",day+"T09:00","clockOutAt",day+"T18:00"}:new String[0];
        submit(employee,type,day,extra);long id=latest("attendance_request");
        write(reviewer,"/attendance/manage/review/"+id+"/approve","comment","確認済み").andExpect(status().isOk());
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,id));
        if(List.of("FIELD","REMOTE","LEAVE","HOLIDAY_WORK").contains(type))assertEquals(1,count("SELECT COUNT(*) FROM work_schedule WHERE user_id=91003 AND work_date=?",day));
        if(type.equals("CORRECTION"))assertEquals(1,count("SELECT COUNT(*) FROM attendance_record WHERE user_id=91003 AND work_date=?",day));
        write(reviewer,"/attendance/manage/review/"+id+"/approve").andExpect(status().isConflict());
        write(employee,"/attendance/manage/requests/"+id+"/cancel").andExpect(status().isConflict());
    }
    @Test void requestCancelRejectAndForeignEmployeeCannotChangeOthers() throws Exception {
        var employee=login(3);var foreign=login(4);var ga=login(2);LocalDate day=workday(5);
        submit(employee,"REMOTE",day);long id=latest("attendance_request");
        write(foreign,"/attendance/manage/requests/"+id+"/cancel").andExpect(status().isConflict());
        write(employee,"/attendance/manage/review/"+id+"/approve").andExpect(status().isForbidden());
        write(ga,"/attendance/manage/review/"+id+"/reject","comment","").andExpect(status().isBadRequest());
        write(ga,"/attendance/manage/review/"+id+"/reject","comment","対象日を再確認").andExpect(status().isOk());assertEquals(0,count("SELECT COUNT(*) FROM work_schedule"));
        submit(employee,"REMOTE",day.plusDays(1));id=latest("attendance_request");write(employee,"/attendance/manage/requests/"+id+"/cancel").andExpect(status().isOk());assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,id));
    }

    @Test void weekendRemainsRestDayUntilHolidayWorkApproval() throws Exception {
        jdbc.update("UPDATE attendance_policy SET work_days_mask=31");
        jdbc.update("UPDATE attendance_policy_history SET work_days_mask=31");
        LocalDate sunday=AttendanceClock.today().plusDays(1);
        while(sunday.getDayOfWeek()!=DayOfWeek.SUNDAY)sunday=sunday.plusDays(1);
        assertEquals(com.example.demo.attendance.DayType.REST_DAY,policies.resolve(91003L,sunday).dayType());
        var employee=login(3);submit(employee,"HOLIDAY_WORK",sunday);long id=latest("attendance_request");
        assertEquals(com.example.demo.attendance.DayType.REST_DAY,policies.resolve(91003L,sunday).dayType());
        write(login(2),"/attendance/manage/review/"+id+"/approve","comment","休日出勤を確認").andExpect(status().isOk());
        assertEquals(com.example.demo.attendance.DayType.WORKDAY,policies.resolve(91003L,sunday).dayType());
    }
    @Test void noticesDraftPublishReadEditInvalidateRestoreAndRoleBoundaries() throws Exception {
        var ga=login(2);var employee=login(3);
        String[] fields={"title","QA社内連絡","content","<script>not executable</script>","category","一般","priority","NORMAL","status","DRAFT"};
        write(employee,"/notices/save",fields).andExpect(status().isForbidden());write(ga,"/notices/save",fields).andExpect(status().isOk());long id=latest("notice");
        read(employee,"/notices/"+id).andExpect(status().isNotFound());
        write(ga,"/notices/save","id",Long.toString(id),"version","0","title","QA公開","content","共有本文","category","一般","priority","IMPORTANT","status","PUBLISHED").andExpect(status().isOk());
        read(employee,"/notices/"+id).andExpect(status().isOk());write(employee,"/notices/"+id+"/read","version","1").andExpect(status().isOk());
        write(ga,"/notices/save","id",Long.toString(id),"version","1","title","QA更新","content","改訂本文","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isOk());
        write(employee,"/notices/"+id+"/read","version","1").andExpect(status().isConflict());
        write(ga,"/notices/"+id+"/deactivate","version","2").andExpect(status().isOk());read(employee,"/notices/"+id).andExpect(status().isNotFound());assertEquals(1,count("SELECT COUNT(*) FROM notice WHERE id=?",id));
        write(ga,"/notices/"+id+"/restore","version","3").andExpect(status().isOk());assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM notice WHERE id=?",String.class,id));
    }
    @Test void passwordChangeOnlyTargetsSelfAndRevokesAllExistingSessions() throws Exception {
        var first=login(3);var second=login(3);String adminHash=jdbc.queryForObject("SELECT password FROM sys_user WHERE id=91001",String.class);
        write(first,"/my-page/password","currentPassword",IsolatedDatabase.ACCOUNT_PASSWORD,"newPassword","short","passwordConfirmation","short").andExpect(status().isBadRequest());
        write(first,"/my-page/password","currentPassword",IsolatedDatabase.ACCOUNT_PASSWORD,"newPassword","ChangedQaPass456!","passwordConfirmation","ChangedQaPass456!","userId","91001").andExpect(status().isOk());
        read(second,"/my-page").andExpect(status().isUnauthorized());assertEquals(adminHash,jdbc.queryForObject("SELECT password FROM sys_user WHERE id=91001",String.class));
        String hash=jdbc.queryForObject("SELECT password FROM sys_user WHERE id=91003",String.class);assertTrue(PasswordHashes.encoder().matches("ChangedQaPass456!",hash));assertFalse(PasswordHashes.encoder().matches(IsolatedDatabase.ACCOUNT_PASSWORD,hash));
    }
    @Test void expiredRegisteredSessionCannotMutateAndLogoutRequiresPost() throws Exception {
        var session=login(3);String ref=(String)session.getAttribute(com.example.security.SessionRegistry.REF);
        jdbc.update("UPDATE security_session_registry SET idle_expires_at=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE ref_id=?",ref);
        write(session,"/attendance/clock-in").andExpect(status().isUnauthorized());assertEquals(0,count("SELECT COUNT(*) FROM attendance_record"));
        session=login(3);write(session,"/logout").andExpect(status().isOk());read(session,"/my-page").andExpect(status().isUnauthorized());
    }
    @Test void auditFailureRollsBackBusinessWrite() throws Exception {
        var admin=login(1);
        jdbc.execute("RENAME TABLE security_audit_log TO qa_unavailable_audit");
        try{write(admin,"/departments","code","QA_ROLLBACK","name","QA監査障害").andExpect(status().isServiceUnavailable());assertEquals(0,count("SELECT COUNT(*) FROM department_master WHERE code='QA_ROLLBACK'"));}
        finally{jdbc.execute("RENAME TABLE qa_unavailable_audit TO security_audit_log");}
    }
    @Test void concurrentDuplicateApplicationsNeverCreateTwoPendingRows() throws Exception {
        var first=login(3);var second=login(3);
        try(var pool=Executors.newFixedThreadPool(2)){
            for(int attempt=0;attempt<6;attempt++){
                LocalDate day=workday(10+attempt*7);var start=new CountDownLatch(1);
                var tasks=List.of(first,second).stream().map(session->pool.submit(()->{start.await();return write(session,"/attendance/manage/requests","type","REMOTE","startDate",day.toString(),"endDate",day.toString(),"reason","並行試験").andReturn().getResponse().getStatus();})).toList();
                start.countDown();var results=new ArrayList<Integer>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));
                assertEquals(1,count("SELECT COUNT(*) FROM attendance_request WHERE user_id=91003 AND start_date=? AND status='PENDING'",day),"二重申請: "+results);
                Collections.sort(results);assertEquals(List.of(200,409),results);
            }
        }
    }
    @Test void concurrentBreakStartCreatesExactlyOneOpenBreak() throws Exception {
        var first=login(3);var second=login(3);write(first,"/attendance/clock-in").andExpect(status().isOk());
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var tasks=List.of(first,second).stream().map(session->pool.submit(()->{start.await();return write(session,"/attendance/break-start").andReturn().getResponse().getStatus();})).toList();
            start.countDown();for(var task:tasks)assertTrue(List.of(200,409).contains(task.get(20,TimeUnit.SECONDS)));
            assertEquals(1,count("SELECT COUNT(*) FROM attendance_break WHERE ended_at IS NULL"));
        }
    }
    @Test void administratorPolicyHolidayAndOvernightScheduleLifecycle() throws Exception {
        var admin=login(1);var ga=login(2);var employee=login(3);LocalDate date=workday(4);
        write(ga,"/attendance/manage/holidays","date",date.toString(),"name","隔離休日").andExpect(status().isForbidden());
        write(employee,"/attendance/manage/schedules","userId","91003","workDate",date.toString(),"dayType","REST_DAY","workMode","OFFICE","breakMinutes","0").andExpect(status().isForbidden());
        write(admin,"/attendance/manage/policy","standardStart","10:00","standardEnd","19:00","breakMinutes","60","lateGraceMinutes","5","earlyGraceMinutes","5","overtimeThresholdMinutes","10","workDay","1","workDay","2","workDay","4","workDay","8","workDay","16").andExpect(status().isOk());
        assertEquals(LocalTime.of(9,0),policies.policyForDate(AttendanceClock.today()).standardStart());assertEquals(LocalTime.of(10,0),policies.policyForDate(AttendanceClock.today().plusDays(1)).standardStart());
        write(admin,"/attendance/manage/holidays","date",date.toString(),"name","隔離休日").andExpect(status().isOk());assertEquals(com.example.demo.attendance.DayType.HOLIDAY,policies.resolve(91003L,date).dayType());
        write(admin,"/attendance/manage/holidays/"+date+"/delete").andExpect(status().isOk());assertEquals(1,count("SELECT delete_flag FROM company_holiday WHERE holiday_date=?",date));assertEquals(com.example.demo.attendance.DayType.WORKDAY,policies.resolve(91003L,date).dayType());
        write(admin,"/attendance/manage/schedules","userId","91003","workDate",date.toString(),"dayType","WORKDAY","workMode","OFFICE","plannedStartAt",date+"T22:00","plannedEndAt",date.plusDays(1)+"T06:00","breakMinutes","60").andExpect(status().isOk());long id=latest("work_schedule");assertEquals(date.plusDays(1).atTime(6,0),policies.resolve(91003L,date).plannedEndAt());
        write(admin,"/attendance/manage/schedules/"+id+"/delete").andExpect(status().isOk());assertEquals(1,count("SELECT delete_flag FROM work_schedule WHERE id=?",id));
        write(admin,"/attendance/manage/schedules","userId","91006","workDate",date.toString(),"dayType","REST_DAY","workMode","OFFICE","breakMinutes","0").andExpect(status().isConflict());
    }
    @Test void salesDepartmentRevocationAndEmployeeScopeAreImmediate() throws Exception {
        var admin=login(1);var employee=login(4);write(admin,"/departments/92002","version","0","status","1","salesEnabled","false").andExpect(status().isOk());
        read(employee,"/sales").andExpect(status().isForbidden());write(employee,"/sales/save","salesMonth","2026-10","department","QA営業一部","salesAmount","1","orderCount","1","customerCount","1","confirmed","true").andExpect(status().isForbidden());assertEquals(0,count("SELECT COUNT(*) FROM sales_data"));
        write(admin,"/users/91004/department","department","QA開発部").andExpect(status().isOk());read(employee,"/my-page").andExpect(status().isOk());read(employee,"/sales").andExpect(status().isForbidden());
        assertEquals("QA開発部",jdbc.queryForObject("SELECT department FROM sys_user WHERE id=91004",String.class));
    }
    @Test void noticeMarkupIsEscapedAndStaleEditDoesNotOverwrite(){
        assertDoesNotThrow(()->{
            var ga=login(2);var employee=login(3);write(ga,"/notices/save","title","<img src=x onerror=alert(1)>","content","<script>alert(1)</script>","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isOk());long id=latest("notice");
            read(employee,"/notices/"+id).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.content").value("<script>alert(1)</script>"));
            String html=read(employee,"/notices").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);assertFalse(html.contains("<img src=x onerror=alert(1)>"));assertTrue(html.contains("&lt;img"));
            write(ga,"/notices/save","id",Long.toString(id),"version","99","title","上書き拒否","content","内容","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isConflict());assertNotEquals("上書き拒否",jdbc.queryForObject("SELECT title FROM notice WHERE id=?",String.class,id));
        });
    }
    @Test void progressiveLoginAndDatabaseTimePrecisionPersistCorrectly(){
        var instant=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-15T10:00:00.123456789Z"));
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant.get();}};
        var protection=new com.example.demo.security.LoginProtectionService(jdbc,users,audit,clock,securitySettings,transactions);
        for(int tier=0;tier<3;tier++){
            for(int i=0;i<5;i++)assertNull(protection.login("QA_EMP_3","incorrect","192.0.2.88").user());
            String key=com.example.demo.security.SecurityRequestContext.hash("user:91003");assertEquals((tier+1)*5,count("SELECT attempts FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",key));
            var denied=protection.login("QA_EMP_3",IsolatedDatabase.ACCOUNT_PASSWORD,"192.0.2.88");assertNull(denied.user());assertEquals((tier+1)*5,count("SELECT attempts FROM security_login_attempt WHERE scope='ACCOUNT' AND key_hash=?",key));
            if(tier<2){assertTrue(denied.retryAfter()>0);instant.set(instant.get().plusSeconds(tier==0?900:3600));}
        }
        instant.set(instant.get().plusSeconds(90000));assertTrue(protection.login("QA_EMP_3",IsolatedDatabase.ACCOUNT_PASSWORD,"192.0.2.88").protectedLogin());
        assertNotNull(protection.login("QA_EMP_4",IsolatedDatabase.ACCOUNT_PASSWORD,"192.0.2.89").user());
        assertEquals(1,count("SELECT COUNT(*) FROM sys_user WHERE id=91003 AND status=1 AND delete_flag=0"));
    }
    @Test void concurrentApproveAndCancelKeepResultAndScheduleAtomic() throws Exception {
        var employee=login(3);var reviewer=login(2);LocalDate date=workday(8);submit(employee,"REMOTE",date);long id=latest("attendance_request");
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var approve=pool.submit(()->{start.await();return write(reviewer,"/attendance/manage/review/"+id+"/approve","comment","並行審査試験").andReturn().getResponse().getStatus();});var cancel=pool.submit(()->{start.await();return write(employee,"/attendance/manage/requests/"+id+"/cancel").andReturn().getResponse().getStatus();});start.countDown();
            var results=new ArrayList<>(List.of(approve.get(20,TimeUnit.SECONDS),cancel.get(20,TimeUnit.SECONDS)));Collections.sort(results);assertEquals(List.of(200,409),results);
            String result=jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,id);assertTrue(List.of("APPROVED","CANCELLED").contains(result));assertEquals(result.equals("APPROVED")?1:0,count("SELECT COUNT(*) FROM work_schedule WHERE user_id=91003 AND work_date=?",date));
        }
    }
    @Test void realHttpCookieRotationCsrfRefreshAndLocalPunchWorkflow() throws Exception {
        var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
        var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        String base="http://127.0.0.1:"+port;
        var page=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,page.statusCode());
        String token=csrfFrom(page.body());String originalCookie=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        var loginRequest=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/users/login")).header("X-CSRF-TOKEN",token).header("X-Requested-With","XMLHttpRequest").header("Content-Type","application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString("userName=QA_EMP_3&password="+java.net.URLEncoder.encode(IsolatedDatabase.ACCOUNT_PASSWORD,StandardCharsets.UTF_8))).build();
        var result=client.send(loginRequest,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,result.statusCode());assertEquals("OK",result.body());assertNotEquals(originalCookie,cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue());
        var own=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/my-page")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,own.statusCode());String fresh=csrfFrom(own.body());assertNotEquals(token,fresh);
        var missing=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/attendance/clock-in")).header("X-Requested-With","XMLHttpRequest").POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();assertEquals(403,client.send(missing,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(0,count("SELECT COUNT(*) FROM attendance_record"));
        var punch=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/attendance/clock-in")).header("X-Requested-With","XMLHttpRequest").header("X-CSRF-TOKEN",fresh).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();assertEquals(200,client.send(punch,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(1,count("SELECT COUNT(*) FROM attendance_record WHERE user_id=91003"));
        var logout=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/logout")).header("X-CSRF-TOKEN",fresh).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();assertEquals(200,client.send(logout,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var denied=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/my-page")).header("X-Requested-With","XMLHttpRequest").GET().build();assertEquals(401,client.send(denied,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    String csrfFrom(String html){var matcher=java.util.regex.Pattern.compile("name=\"_csrf\" content=\"([^\"]+)\"").matcher(html);assertTrue(matcher.find(),"CSRF metaが必要です。");return matcher.group(1);}
    @ParameterizedTest @ValueSource(strings={"clock-in","clock-out","break-start","break-end"})
    void everyPunchTransitionIsSingleCommitAcrossTwoSessions(String action) throws Exception {
        var first=login(3);var second=login(3);
        if(!action.equals("clock-in"))write(first,"/attendance/clock-in").andExpect(status().isOk());
        if(action.equals("break-end"))write(first,"/attendance/break-start").andExpect(status().isOk());
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var tasks=List.of(first,second).stream().map(session->pool.submit(()->{start.await();return write(session,"/attendance/"+action).andReturn().getResponse().getStatus();})).toList();start.countDown();var results=new ArrayList<Integer>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));Collections.sort(results);assertEquals(List.of(200,409),results);
            String event=action.toUpperCase(Locale.ROOT).replace('-','_');assertEquals(1,count("SELECT COUNT(*) FROM attendance_event WHERE user_id=91003 AND event_type=?",event));
        }
    }
    @Test void simultaneousCrossAdministratorDisableNeverRemovesLastAdministrator() throws Exception {
        var first=login(1);var second=login(7);
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var one=pool.submit(()->{start.await();return write(first,"/users/91007/deactivate").andReturn().getResponse().getStatus();});var two=pool.submit(()->{start.await();return write(second,"/users/91001/deactivate").andReturn().getResponse().getStatus();});start.countDown();
            assertTrue(List.of(200,401,409).contains(one.get(20,TimeUnit.SECONDS)));assertTrue(List.of(200,401,409).contains(two.get(20,TimeUnit.SECONDS)));assertEquals(1,count("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN' AND status=1 AND delete_flag=0"));
        }
    }
    @RepeatedTest(4)
    void concurrentLeaveApprovalAndClockInCannotCommitContradictoryStates() throws Exception {
        var employee=login(3);var reviewer=login(2);LocalDate date=AttendanceClock.today();
        submit(employee,"LEAVE",date);long requestId=latest("attendance_request");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var approval=pool.submit(()->{start.await();return write(reviewer,"/attendance/manage/review/"+requestId+"/approve").andReturn().getResponse().getStatus();});
            var punch=pool.submit(()->{start.await();return write(employee,"/attendance/clock-in").andReturn().getResponse().getStatus();});
            start.countDown();
            var results=new ArrayList<>(List.of(approval.get(20,TimeUnit.SECONDS),punch.get(20,TimeUnit.SECONDS)));
            Collections.sort(results);assertEquals(List.of(200,409),results);
            boolean approved="APPROVED".equals(jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,requestId));
            assertEquals(approved?0:1,count("SELECT COUNT(*) FROM attendance_record WHERE user_id=91003 AND work_date=?",date));
            assertEquals(approved?1:0,count("SELECT COUNT(*) FROM work_schedule WHERE user_id=91003 AND work_date=? AND day_type='LEAVE' AND delete_flag=0",date));
        }
    }

    private long seedCorrectionRecord(LocalDate date, boolean openRecord, boolean openBreak) {
        jdbc.update("INSERT INTO attendance_record(user_id,work_date,clock_in_at,clock_out_at,created_at,updated_at,version,delete_flag) VALUES(91003,?,?,?,?,?,0,0)",
                date,date.atTime(9,0),openRecord?null:date.atTime(18,0),AttendanceClock.now(),AttendanceClock.now());
        long id=latest("attendance_record");
        jdbc.update("INSERT INTO attendance_break(attendance_record_id,started_at,ended_at,version,delete_flag) VALUES(?,?,?,0,0)",id,date.atTime(12,0),openBreak?null:date.atTime(13,0));
        return id;
    }

    @ParameterizedTest @ValueSource(strings={"IN_AFTER_BREAK_START","OUT_BEFORE_BREAK_END","OPEN_BREAK_WITH_OUT"})
    void correctionCannotContradictPersistedBreaks(String conflict) throws Exception {
        var employee=login(3);var reviewer=login(2);LocalDate date=workday(-2);
        boolean open=conflict.equals("OPEN_BREAK_WITH_OUT");long recordId=seedCorrectionRecord(date,open,open);
        String in=conflict.equals("IN_AFTER_BREAK_START")?date+"T12:30":date+"T09:00";
        String out=conflict.equals("OUT_BEFORE_BREAK_END")?date+"T12:30":date+"T18:00";
        submit(employee,"CORRECTION",date,"clockInAt",in,"clockOutAt",out);long requestId=latest("attendance_request");
        write(reviewer,"/attendance/manage/review/"+requestId+"/approve").andExpect(status().isConflict());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,requestId));
        assertEquals(date.atTime(9,0),jdbc.queryForObject("SELECT clock_in_at FROM attendance_record WHERE id=?",LocalDateTime.class,recordId));
        assertEquals(open?null:date.atTime(18,0),jdbc.queryForObject("SELECT clock_out_at FROM attendance_record WHERE id=?",LocalDateTime.class,recordId));
        assertEquals(1,count("SELECT COUNT(*) FROM attendance_break WHERE attendance_record_id=? AND delete_flag=0",recordId));
        assertEquals(0,count("SELECT COUNT(*) FROM attendance_event WHERE event_type='CORRECTION_APPLIED'"));
    }

    @Test void correctionCannotCreateAnotherUnclosedWorkRecord() throws Exception {
        var employee=login(3);var reviewer=login(2);LocalDate date=workday(-2);
        seedCorrectionRecord(date,true,false);
        LocalDate next=date.plusDays(1);
        submit(employee,"CORRECTION",next,"clockInAt",next+"T09:00");long requestId=latest("attendance_request");
        write(reviewer,"/attendance/manage/review/"+requestId+"/approve").andExpect(status().isConflict());
        assertEquals(1,count("SELECT COUNT(*) FROM attendance_record WHERE user_id=91003 AND clock_out_at IS NULL AND delete_flag=0"));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,requestId));
    }

    @Test void validCorrectionPreservesBreaksAndRecomputesWorkingMinutes() throws Exception {
        var employee=login(3);var reviewer=login(2);LocalDate date=workday(-2);
        long recordId=seedCorrectionRecord(date,true,false);
        submit(employee,"CORRECTION",date,"clockInAt",date+"T08:00","clockOutAt",date+"T19:00");long requestId=latest("attendance_request");
        write(reviewer,"/attendance/manage/review/"+requestId+"/approve").andExpect(status().isOk());
        assertEquals(1,count("SELECT COUNT(*) FROM attendance_break WHERE attendance_record_id=? AND delete_flag=0",recordId));
        var view=days.evaluate(userRepository.findById(91003L).orElseThrow(),date);
        assertEquals(60,view.getBreakMinutes());assertEquals(600,view.getWorkedMinutes());
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,requestId));
    }

    @ParameterizedTest @ValueSource(strings={"09:00","23:45"})
    void commonPolicyCannotHaveMoreBreakThanShift(String start) throws Exception {
        var admin=login(1);String end=start.equals("09:00")?"09:30":"00:15";
        write(admin,"/attendance/manage/policy","standardStart",start,"standardEnd",end,"breakMinutes","31","lateGraceMinutes","0","earlyGraceMinutes","0","overtimeThresholdMinutes","0","workDay","1")
                .andExpect(status().isBadRequest());
        assertEquals(LocalTime.of(9,0),policies.currentPolicy().getStandardStart());
        assertEquals(1,count("SELECT COUNT(*) FROM attendance_policy_history"));
    }

    @ParameterizedTest @ValueSource(strings={"DAY","OVERNIGHT"})
    void individualScheduleCannotHaveMoreBreakThanShift(String shift) throws Exception {
        var admin=login(1);LocalDate date=workday(5);
        LocalDateTime start=shift.equals("DAY")?date.atTime(9,0):date.atTime(23,45);
        write(admin,"/attendance/manage/schedules","userId","91003","workDate",date.toString(),"dayType","WORKDAY","workMode","OFFICE","plannedStartAt",start.toString(),"plannedEndAt",start.plusMinutes(30).toString(),"breakMinutes","31")
                .andExpect(status().isBadRequest());
        assertEquals(0,count("SELECT COUNT(*) FROM work_schedule"));
    }

    @Test void breakDurationBoundaryAndOvernightScheduleRemainAllowed() throws Exception {
        var admin=login(1);LocalDate date=workday(5);
        write(admin,"/attendance/manage/policy","standardStart","23:45","standardEnd","00:15","breakMinutes","30","lateGraceMinutes","0","earlyGraceMinutes","0","overtimeThresholdMinutes","0","workDay","1")
                .andExpect(status().isOk());
        write(admin,"/attendance/manage/schedules","userId","91003","workDate",date.toString(),"dayType","WORKDAY","workMode","OFFICE","plannedStartAt",date+"T23:45","plannedEndAt",date.plusDays(1)+"T00:15","breakMinutes","30")
                .andExpect(status().isOk());
        assertEquals(30,policies.resolve(91003L,date).breakMinutes());
    }

    @RepeatedTest(5)
    void sameEmployeeConcurrentNoticeReadsAreIdempotent() throws Exception {
        var editor=login(2);var first=login(3);var second=login(3);
        write(editor,"/notices/save","title","隔離並行既読試験","content","複数画面で同じ公告を確認します。","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isOk());
        long id=latest("notice");
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var tasks=List.of(first,second).stream().map(session->pool.submit(()->{start.await();return write(session,"/notices/"+id+"/read","version","0").andReturn().getResponse().getStatus();})).toList();
            start.countDown();
            var results=new ArrayList<Integer>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));
            assertEquals(List.of(200,200),results,"同じ公開版の既読は複数画面から重複しても成功しなければなりません。");
            assertEquals(1,count("SELECT COUNT(*) FROM notice_read WHERE notice_id=? AND user_id=91003",id));
            assertEquals(0,count("SELECT read_version FROM notice_read WHERE notice_id=? AND user_id=91003",id));
        }
    }

    @Test void concurrentSalesCreationDoesNotDuplicateDashboardTotals() throws Exception {
        var first=login(4);var second=login(4);var admin=login(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var tasks=List.of(first,second).stream().map(session->pool.submit(()->{
                start.await();return write(session,"/sales/save","salesMonth","2026-10","department","QA営業一部","salesAmount","12345.67","orderCount","12","customerCount","4","confirmed","true").andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();var results=new ArrayList<Integer>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));Collections.sort(results);
            assertEquals(List.of(200,409),results);assertEquals(1,count("SELECT COUNT(*) FROM sales_data"));
            read(admin,"/api/dashboard").andExpect(status().isOk()).andExpect(jsonPath("$.summary.totalSales").value(12345.67)).andExpect(jsonPath("$.summary.totalOrders").value(12)).andExpect(jsonPath("$.summary.totalCustomers").value(4));
        }
    }

    @Test void concurrentNoticeEditsNeverSilentlyOverwriteAnotherEditor() throws Exception {
        var first=login(1);var second=login(2);
        write(first,"/notices/save","title","隔離競合編集試験","content","原本","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isOk());long id=latest("notice");
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(var session:List.of(first,second))tasks.add(pool.submit(()->{start.await();return write(session,"/notices/save","id",Long.toString(id),"version","0","title","並行修正","content","変更本文","category","一般","priority","NORMAL","status","PUBLISHED").andReturn().getResponse().getStatus();}));
            start.countDown();var results=new ArrayList<Integer>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));Collections.sort(results);
            assertEquals(List.of(200,409),results);assertEquals(1,count("SELECT version FROM notice WHERE id=?",id));
        }
    }

    @RepeatedTest(3)
    void publicationChangeAndReadKeepDisplayedVersionAndUnreadCountConsistent() throws Exception {
        var editor=login(2);var employee=login(3);
        write(editor,"/notices/save","title","隔離版数確認","content","初版","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isOk());long id=latest("notice");
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var reading=pool.submit(()->{start.await();return write(employee,"/notices/"+id+"/read","version","0").andReturn().getResponse().getStatus();});
            var editing=pool.submit(()->{start.await();return write(editor,"/notices/save","id",Long.toString(id),"version","0","title","隔離版数確認","content","改訂版","category","一般","priority","NORMAL","status","PUBLISHED").andReturn().getResponse().getStatus();});
            start.countDown();assertTrue(List.of(200,409).contains(reading.get(20,TimeUnit.SECONDS)));assertEquals(200,editing.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,count("SELECT version FROM notice WHERE id=?",id));
        assertEquals(0,count("SELECT COUNT(*) FROM notice_read WHERE notice_id=? AND read_version<>0",id));
        write(employee,"/notices/"+id+"/read","version","0").andExpect(status().isConflict());
        read(employee,"/notices?fragment=notice-count").andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("1 件")));
        write(employee,"/notices/"+id+"/read","version","1").andExpect(status().isOk());
        write(employee,"/notices/"+id+"/read","version","1").andExpect(status().isOk());
        assertEquals(1,count("SELECT COUNT(*) FROM notice_read WHERE notice_id=? AND user_id=91003 AND read_version=1",id));
        write(employee,"/notices/"+id+"/read","version","0").andExpect(status().isConflict());
        assertEquals(1,count("SELECT read_version FROM notice_read WHERE notice_id=? AND user_id=91003",id));
    }

    @ParameterizedTest @ValueSource(strings={"FUTURE_IN_CLOCK_OUT","FUTURE_IN_BREAK_START","FUTURE_BREAK_START_BREAK_END","FUTURE_BREAK_END_CLOCK_OUT","FUTURE_BREAK_END_BREAK_START"})
    void actualPunchCannotMoveBehindPersistedClockOrBreak(String scenario) throws Exception {
        var employee=login(3);LocalDateTime now=AttendanceClock.now();
        LocalDateTime in=scenario.startsWith("FUTURE_IN")?now.plusHours(2):now.minusHours(2);
        jdbc.update("INSERT INTO attendance_record(user_id,work_date,clock_in_at,created_at,updated_at,version,delete_flag) VALUES(91003,?,?,?,?,0,0)",in.toLocalDate(),in,now,now);
        long id=latest("attendance_record");
        if(!scenario.startsWith("FUTURE_IN")){
            boolean open=scenario.equals("FUTURE_BREAK_START_BREAK_END");
            jdbc.update("INSERT INTO attendance_break(attendance_record_id,started_at,ended_at,version,delete_flag) VALUES(?,?,?,0,0)",id,open?now.plusHours(1):now.minusHours(1),open?null:now.plusHours(1));
        }
        var before=jdbc.queryForMap("SELECT * FROM attendance_record WHERE id=?",id);
        var beforeBreaks=jdbc.queryForList("SELECT * FROM attendance_break WHERE attendance_record_id=? ORDER BY id",id);
        String action=scenario.endsWith("CLOCK_OUT")?"clock-out":scenario.endsWith("BREAK_END")?"break-end":"break-start";
        write(employee,"/attendance/"+action).andExpect(status().isConflict());
        assertEquals(before,jdbc.queryForMap("SELECT * FROM attendance_record WHERE id=?",id));
        assertEquals(beforeBreaks,jdbc.queryForList("SELECT * FROM attendance_break WHERE attendance_record_id=? ORDER BY id",id));
        assertEquals(0,count("SELECT COUNT(*) FROM attendance_event"));
    }

    @Test void approvedFutureCorrectionDoesNotPermitEarlierActualClockOut() throws Exception {
        var employee=login(3);var reviewer=login(2);LocalDate day=workday(1);
        submit(employee,"CORRECTION",day,"clockInAt",day+"T09:00");long requestId=latest("attendance_request");
        write(reviewer,"/attendance/manage/review/"+requestId+"/approve").andExpect(status().isOk());
        write(employee,"/attendance/clock-out").andExpect(status().isConflict());
        assertEquals(1,count("SELECT COUNT(*) FROM attendance_record WHERE user_id=91003 AND clock_out_at IS NULL"));
        assertEquals(0,count("SELECT COUNT(*) FROM attendance_event WHERE event_type='CLOCK_OUT'"));
    }

    private Map<String,List<Map<String,Object>>> businessSnapshot() {
        Map<String,List<Map<String,Object>>> snapshot=new LinkedHashMap<>();
        for(String table:List.of("department_master","sys_user","sales_data","notice","notice_read","attendance_policy","attendance_policy_history","company_holiday","work_schedule","attendance_record","attendance_break","attendance_event","attendance_request","attendance_audit_log")){
            snapshot.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        }
        return snapshot;
    }

    private record AuditMutation(MockHttpSession session,String path,String... parameters) {}

    @ParameterizedTest @ValueSource(strings={"USER_REGISTER","USER_ROLE","USER_DISABLE","USER_ENABLE","USER_DEPARTMENT","DEPARTMENT_UPDATE","SALES_SAVE","NOTICE_SAVE","NOTICE_READ","NOTICE_DEACTIVATE","NOTICE_RESTORE","CLOCK_IN","CLOCK_OUT","BREAK_START","BREAK_END","REQUEST_SUBMIT","REQUEST_CANCEL","REQUEST_APPROVE","REQUEST_REJECT","POLICY","SCHEDULE","SCHEDULE_DELETE","HOLIDAY","HOLIDAY_DELETE","PASSWORD"})
    void everyBusinessMutationRollsBackWhenAuditCannotBeSaved(String operation) throws Exception {
        var admin=login(1);var employee=login(3);var reviewer=login(2);var seller=login(4);LocalDate day=workday(7);
        long noticeId=0,requestId=0,scheduleId=0;
        if(operation.startsWith("NOTICE_")){
            write(reviewer,"/notices/save","title","隔離監査原本","content","保存前本文","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isOk());noticeId=latest("notice");
            if(operation.equals("NOTICE_RESTORE"))write(reviewer,"/notices/"+noticeId+"/deactivate","version","0").andExpect(status().isOk());
        }
        if(List.of("REQUEST_CANCEL","REQUEST_APPROVE","REQUEST_REJECT").contains(operation)){
            submit(employee,"REMOTE",day);requestId=latest("attendance_request");
        }
        if(List.of("CLOCK_OUT","BREAK_START","BREAK_END").contains(operation))write(employee,"/attendance/clock-in").andExpect(status().isOk());
        if(operation.equals("BREAK_END"))write(employee,"/attendance/break-start").andExpect(status().isOk());
        if(operation.equals("SCHEDULE_DELETE")){
            write(admin,"/attendance/manage/schedules","userId","91003","workDate",day.toString(),"dayType","WORKDAY","workMode","OFFICE","plannedStartAt",day+"T09:00","plannedEndAt",day+"T18:00","breakMinutes","60").andExpect(status().isOk());scheduleId=latest("work_schedule");
        }
        if(operation.equals("HOLIDAY_DELETE"))write(admin,"/attendance/manage/holidays","date",day.toString(),"name","隔離休日原本").andExpect(status().isOk());
        var command=switch(operation){
            case "USER_REGISTER"->new AuditMutation(admin,"/users/save","username","QA_NEW_AUDIT","realName","隔離登録","password",IsolatedDatabase.ACCOUNT_PASSWORD,"passwordConfirmation",IsolatedDatabase.ACCOUNT_PASSWORD,"department","QA開発部","role","EMPLOYEE");
            case "USER_ROLE"->new AuditMutation(admin,"/users/91004/role","role","GENERAL_AFFAIRS");
            case "USER_DISABLE"->new AuditMutation(admin,"/users/91004/deactivate");
            case "USER_ENABLE"->new AuditMutation(admin,"/users/91006/activate");
            case "USER_DEPARTMENT"->new AuditMutation(admin,"/users/91004/department","department","QA開発部");
            case "DEPARTMENT_UPDATE"->new AuditMutation(admin,"/departments/92002","version","0","status","1","salesEnabled","false");
            case "SALES_SAVE"->new AuditMutation(seller,"/sales/save","salesMonth","2026-10","department","QA営業一部","salesAmount","100","orderCount","2","customerCount","1","confirmed","true");
            case "NOTICE_SAVE"->new AuditMutation(reviewer,"/notices/save","id",Long.toString(noticeId),"version","0","title","更新後件名","content","更新後本文","category","営業","priority","IMPORTANT","status","DRAFT");
            case "NOTICE_READ"->new AuditMutation(employee,"/notices/"+noticeId+"/read","version","0");
            case "NOTICE_DEACTIVATE"->new AuditMutation(reviewer,"/notices/"+noticeId+"/deactivate","version","0");
            case "NOTICE_RESTORE"->new AuditMutation(reviewer,"/notices/"+noticeId+"/restore","version","1");
            case "CLOCK_IN"->new AuditMutation(employee,"/attendance/clock-in");
            case "CLOCK_OUT"->new AuditMutation(employee,"/attendance/clock-out");
            case "BREAK_START"->new AuditMutation(employee,"/attendance/break-start");
            case "BREAK_END"->new AuditMutation(employee,"/attendance/break-end");
            case "REQUEST_SUBMIT"->new AuditMutation(employee,"/attendance/manage/requests","type","REMOTE","startDate",day.toString(),"endDate",day.toString(),"reason","隔離申請");
            case "REQUEST_CANCEL"->new AuditMutation(employee,"/attendance/manage/requests/"+requestId+"/cancel");
            case "REQUEST_APPROVE"->new AuditMutation(reviewer,"/attendance/manage/review/"+requestId+"/approve");
            case "REQUEST_REJECT"->new AuditMutation(reviewer,"/attendance/manage/review/"+requestId+"/reject","comment","隔離差戻し");
            case "POLICY"->new AuditMutation(admin,"/attendance/manage/policy","standardStart","10:00","standardEnd","19:00","breakMinutes","60","lateGraceMinutes","1","earlyGraceMinutes","1","overtimeThresholdMinutes","1","workDay","1");
            case "SCHEDULE"->new AuditMutation(admin,"/attendance/manage/schedules","userId","91003","workDate",day.toString(),"dayType","WORKDAY","workMode","REMOTE","plannedStartAt",day+"T09:00","plannedEndAt",day+"T18:00","breakMinutes","60");
            case "SCHEDULE_DELETE"->new AuditMutation(admin,"/attendance/manage/schedules/"+scheduleId+"/delete");
            case "HOLIDAY"->new AuditMutation(admin,"/attendance/manage/holidays","date",day.toString(),"name","隔離休日");
            case "HOLIDAY_DELETE"->new AuditMutation(admin,"/attendance/manage/holidays/"+day+"/delete");
            case "PASSWORD"->new AuditMutation(employee,"/my-page/password","currentPassword",IsolatedDatabase.ACCOUNT_PASSWORD,"newPassword","ChangedQaPass789!","passwordConfirmation","ChangedQaPass789!");
            default->throw new IllegalArgumentException(operation);
        };
        var snapshot=businessSnapshot();
        if(operation.equals("PASSWORD")){
            // 本人再認証の監査は許可し、実際のパスワード更新後の監査だけを失敗させる。
            jdbc.execute("CREATE TRIGGER qa_reject_password_audit BEFORE INSERT ON security_audit_log FOR EACH ROW BEGIN IF NEW.operation_code='PASSWORD_CHANGE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA password audit unavailable'; END IF; END");
        }else{jdbc.execute("RENAME TABLE security_audit_log TO qa_unavailable_audit");}
        try{
            write(command.session(),command.path(),command.parameters()).andExpect(status().isServiceUnavailable());
            assertEquals(snapshot,businessSnapshot(),"監査保存失敗では業務行・版数・日時・業務監査をすべて元に戻します。"+operation);
        }finally{
            if(operation.equals("PASSWORD"))jdbc.execute("DROP TRIGGER qa_reject_password_audit");
            else jdbc.execute("RENAME TABLE qa_unavailable_audit TO security_audit_log");
        }
        if(operation.equals("PASSWORD"))read(employee,"/my-page").andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(ints={1,2,3,4,5})
    void remainingRoleAndCsrfBoundariesCoverEveryMutationRoute(int actor) throws Exception {
        var session=login(actor);LocalDate day=workday(9);
        var snapshot=businessSnapshot();
        for(String path:List.of("/users/save","/users/91004/role","/users/91004/department","/users/91004/deactivate","/users/91006/activate","/departments","/departments/92001","/sales/save","/notices/save","/notices/1/read","/notices/1/deactivate","/notices/1/restore","/attendance/clock-in","/attendance/clock-out","/attendance/break-start","/attendance/break-end","/attendance/manage/requests","/attendance/manage/requests/1/cancel","/attendance/manage/review/1/approve","/attendance/manage/review/1/reject","/attendance/manage/policy","/attendance/manage/schedules","/attendance/manage/schedules/1/delete","/attendance/manage/holidays","/attendance/manage/holidays/"+day+"/delete","/my-page/password","/session/activity","/logout")){
            mvc.perform(ajax(post(path)).session(session)).andExpect(status().isForbidden());
        }
        assertEquals(snapshot,businessSnapshot());
        for(String fragment:List.of("history","review","reviewed","rules","schedule-list","holiday-list")){
            boolean allowed=fragment.equals("history")||actor==1||actor==2&&List.of("review","reviewed").contains(fragment);
            read(session,"/attendance/manage?fragment="+fragment).andExpect(status().is(allowed?200:403));
        }
        read(session,"/departments?fragment=department-data").andExpect(status().is(actor==1?200:403));
        for(String fragment:List.of("employee-data","employee-count"))read(session,"/users?fragment="+fragment).andExpect(status().is(actor==1?200:403));
        read(session,"/sales?fragment=sales-data").andExpect(status().is(actor==1||actor>=4?200:403));
        write(session,"/attendance/manage/requests","type","REMOTE","startDate",day.toString(),"endDate",day.toString(),"reason","隔離権限確認").andExpect(status().is(actor>=3?200:403));
        write(session,"/attendance/manage/schedules","userId","91003","workDate",day.toString(),"dayType","REST_DAY","workMode","OFFICE","breakMinutes","0").andExpect(status().is(actor==1?200:403));
        write(session,"/attendance/manage/holidays","date",day.toString(),"name","隔離権限休日").andExpect(status().is(actor==1?200:403));
        write(session,"/attendance/manage/schedules/"+(actor==1?latest("work_schedule"):999999)+"/delete").andExpect(status().is(actor==1?200:403));
        write(session,"/attendance/manage/holidays/"+day+"/delete").andExpect(status().is(actor==1?200:403));
        write(session,"/notices/save","title","隔離権限公告","content","本文","category","一般","priority","NORMAL","status","DRAFT").andExpect(status().is(actor<=2?200:403));
        write(session,"/session/activity").andExpect(status().isOk());
        write(session,"/logout").andExpect(status().isOk());read(session,"/my-page").andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings={"LEAVE","HOLIDAY_WORK"})
    void multiDayApprovalFailureRollsBackAlreadyAppliedEarlierDays(String type) throws Exception {
        var admin=login(1);var employee=login(3);var reviewer=login(2);LocalDate first=workday(4),second=first.plusDays(1);
        while(second.getDayOfWeek().getValue()>5)second=second.plusDays(1);
        if(type.equals("LEAVE")){
            seedCorrectionRecord(second,false,false);
        }else{
            write(admin,"/attendance/manage/holidays","date",first.toString(),"name","隔離休日").andExpect(status().isOk());
            write(admin,"/attendance/manage/schedules","userId","91003","workDate",second.toString(),"dayType","WORKDAY","workMode","OFFICE","plannedStartAt",second+"T09:00","plannedEndAt",second+"T18:00","breakMinutes","60").andExpect(status().isOk());
        }
        write(employee,"/attendance/manage/requests","type",type,"startDate",first.toString(),"endDate",second.toString(),"reason","複数日の原子性確認").andExpect(status().isOk());long id=latest("attendance_request");
        var snapshot=businessSnapshot();
        write(reviewer,"/attendance/manage/review/"+id+"/approve").andExpect(status().isConflict());
        assertEquals(snapshot,businessSnapshot());assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM attendance_request WHERE id=?",String.class,id));
    }

    @Test void revokedReviewerSessionCannotWriteAnyFormerlyAllowedBusinessAction() throws Exception {
        var admin=login(1);var reviewer=login(2);var employee=login(3);LocalDate day=workday(3);
        submit(employee,"REMOTE",day);long id=latest("attendance_request");
        write(admin,"/users/91002/role","role","EMPLOYEE").andExpect(status().isOk());var snapshot=businessSnapshot();
        write(reviewer,"/attendance/manage/review/"+id+"/approve").andExpect(status().isUnauthorized());
        write(reviewer,"/notices/save","title","失効会話","content","拒否される本文","category","一般","priority","NORMAL","status","PUBLISHED").andExpect(status().isUnauthorized());
        assertEquals(snapshot,businessSnapshot());read(login(2),"/attendance/manage?fragment=review").andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings={"EMPLOYEE_DEPARTMENT","DISABLED_EMPLOYEE","ADMIN_DOWNGRADE"})
    void salesWriteRechecksActorAfterConcurrentAdministrativeChange(String change) throws Exception {
        int actor=change.equals("ADMIN_DOWNGRADE")?1:4;
        // Controllerが本人を読んだ直後、別要求の管理変更が先にコミットした順序を再現する。
        var stale=userRepository.findById(91000L+actor).orElseThrow();
        var admin=login(7);
        if(change.equals("EMPLOYEE_DEPARTMENT"))write(admin,"/users/91004/department","department","QA営業二部").andExpect(status().isOk());
        else if(change.equals("DISABLED_EMPLOYEE"))write(admin,"/users/91004/deactivate").andExpect(status().isOk());
        else write(admin,"/users/91001/role","role","EMPLOYEE").andExpect(status().isOk());
        var before=businessSnapshot();
        var form=new com.example.demo.sales.SalesForm(null,null,"2026-11",change.equals("ADMIN_DOWNGRADE")?"QA開発部":"QA営業一部",new java.math.BigDecimal("123.45"),1,1,"排他境界試験",true);
        var rejected=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->salesService.save(stale,form));
        assertEquals(403,rejected.getStatusCode().value());
        assertEquals(before,businessSnapshot(),"管理変更後に古い所属・権限で営業実績を書き込めません。");
    }

    @ParameterizedTest @ValueSource(strings={"SALES_REVOKED","DEPARTMENT_CHANGED"})
    void salesHttpWriteWaitsForAdministrativeCommitThenRechecksPermission(String change) throws Exception {
        var employee=login(4);
        try(var executor=Executors.newSingleThreadExecutor()){
            var future=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM security_management_guard WHERE id=1 FOR UPDATE",Long.class);
                if(change.equals("SALES_REVOKED"))departments.update(92002L,0L,false,1);
                else departments.assignEmployee(91004L,"QA営業二部");
                var started=new CountDownLatch(1);
                future.set(executor.submit(()->{started.countDown();return write(employee,"/sales/save","salesMonth","2026-11","department","QA営業一部","salesAmount","123","orderCount","1","customerCount","1","confirmed","true").andReturn().getResponse().getStatus();}));
                try{assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->future.get().get(250,TimeUnit.MILLISECONDS),"管理変更コミット前に営業保存を確定しません。");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            });
            assertEquals(403,future.get().get(20,TimeUnit.SECONDS));
            assertEquals(0,count("SELECT COUNT(*) FROM sales_data"));
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="OA_BROWSER_ACCEPTANCE",matches="true")
    void realBrowserUsesAjaxAgainstIsolatedApplication() throws Exception {
        Path log=Path.of("D:/oa-maintenance/business-acceptance-20261015/browser-process.log");
        var builder=new ProcessBuilder("C:/Users/27184/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe","src/test/js/isolated-browser-workflows.cjs","http://127.0.0.1:"+port,workday(5).toString());
        builder.environment().put("NODE_PATH","C:/Users/27184/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules");builder.environment().put("OA_QA_PORT",Integer.toString(port));builder.redirectErrorStream(true).redirectOutput(log.toFile());var process=builder.start();
        if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();fail("隔離ブラウザー試験が時間内に終了しません。"+log);}
        assertEquals(0,process.exitValue(),()->{try{return Files.readString(log);}catch(Exception failure){return log.toString();}});
    }
}
