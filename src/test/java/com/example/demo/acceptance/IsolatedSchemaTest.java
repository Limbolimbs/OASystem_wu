package com.example.demo.acceptance;

import java.nio.file.*;
import java.sql.*;
import java.util.List;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import com.example.demo.entity.*;
import com.example.demo.security.PasswordHashes;

@EnabledIfEnvironmentVariable(named="OA_ACCEPTANCE_BOOTSTRAP",matches="true")
class IsolatedSchemaTest {
    @Test void initializeDedicatedEmptyTestDatabase() throws Exception {
        String server=IsolatedDatabase.URL.replace("/"+IsolatedDatabase.NAME+"?","/?");
        Connection initial;
        try { initial=DriverManager.getConnection(server,"root",IsolatedDatabase.PASSWORD); }
        catch(SQLException unavailable){ initial=DriverManager.getConnection(server,"root",""); }
        try(var connection=initial;var sql=connection.createStatement()){
            IsolatedDatabase.require(connection);
            sql.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+IsolatedDatabase.PASSWORD+"'");
            // 既存DBを削除・再作成しない。重複時は失敗して手動確認を求める。
            sql.execute("CREATE DATABASE IF NOT EXISTS "+IsolatedDatabase.NAME+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+IsolatedDatabase.NAME+"'")){
                rows.next();org.junit.jupiter.api.Assertions.assertEquals(0,rows.getInt(1),"空でないテストDBは再作成しません。");
            }
        }
        var config=new Configuration();
        List.of(User.class,Department.class,SalesData.class,AttendanceRecord.class,AttendancePolicy.class,
                AttendancePolicyHistory.class,CompanyHoliday.class,WorkSchedule.class,AttendanceRequest.class,
                AttendanceBreak.class,AttendanceEvent.class,AttendanceAuditLog.class,Notice.class,NoticeRead.class).forEach(config::addAnnotatedClass);
        config.setProperty("hibernate.connection.url",IsolatedDatabase.URL);
        config.setProperty("hibernate.connection.username","root");config.setProperty("hibernate.connection.password",IsolatedDatabase.PASSWORD);
        config.setProperty("hibernate.hbm2ddl.auto","create-only");
        config.setProperty("hibernate.hbm2ddl.halt_on_error","true");
        config.setProperty("hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl");
        try(var factory=config.buildSessionFactory()) { factory.getProperties(); }
        try(var connection=IsolatedDatabase.connection()){
            for(String name:List.of("08_password_hash.sql","09_session_login_audit.sql","10_security_console.sql","11_access_evidence.sql","12_console_members.sql","13_progressive_protection.sql","14_session_management.sql")){
                String source=Files.readString(Path.of("src/main/resources/database",name)).replace("oa_system",IsolatedDatabase.NAME);
                ScriptUtils.executeSqlScript(connection,new ByteArrayResource(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            var sql=connection.createStatement();
            sql.execute("CREATE TABLE qa_environment_marker (id INT PRIMARY KEY, purpose VARCHAR(100) NOT NULL)");
            sql.execute("INSERT INTO qa_environment_marker VALUES(1,'isolated business acceptance only')");
            String hash=PasswordHashes.encoder().encode(IsolatedDatabase.ACCOUNT_PASSWORD);
            for(int i=1;i<=7;i++){
                String role=i==1||i==7?"ADMIN":i==2?"GENERAL_AFFAIRS":"EMPLOYEE";
                String department=i==4?"QA営業一部":i==5?"QA営業二部":i==3?"QA開発部":"QA総務部";
                try(var insert=connection.prepareStatement("INSERT INTO sys_user(id,username,password,real_name,department,role,status,authentication_version,created_at,updated_at,delete_flag,deleted_at) VALUES(?,?,?,?,?,?,?,0,NOW(6),NOW(6),?,?)")){
                    insert.setLong(1,91000+i);insert.setString(2,"QA_EMP_"+i);insert.setString(3,hash);insert.setString(4,"試験社員"+i);insert.setString(5,department);insert.setString(6,role);
                    insert.setInt(7,i==6?0:1);insert.setInt(8,i==6?1:0);insert.setTimestamp(9,i==6?new Timestamp(System.currentTimeMillis()):null);insert.executeUpdate();
                }
            }
            for(int i=1;i<=4;i++){
                String name=List.of("QA総務部","QA営業一部","QA営業二部","QA開発部").get(i-1);
                try(var insert=connection.prepareStatement("INSERT INTO department_master(id,code,name,sales_enabled,status,version,created_at,updated_at,delete_flag) VALUES(?,?,?,?,1,0,NOW(6),NOW(6),0)")){
                    insert.setLong(1,92000+i);insert.setString(2,"QA_DEPT_"+i);insert.setString(3,name);insert.setBoolean(4,i==2||i==3);insert.executeUpdate();
                }
            }
            sql.execute("INSERT INTO attendance_policy(id,standard_start,standard_end,break_minutes,late_grace_minutes,early_grace_minutes,overtime_threshold_minutes,work_days_mask,version,updated_at,delete_flag) VALUES(1,'09:00','18:00',60,0,0,0,31,0,NOW(6),0)");
        }
        System.out.println("ISOLATED_QA_SCHEMA_READY");
    }
}
