package com.example.demo.acceptance;

import java.sql.*;
import java.util.List;
import javax.sql.DataSource;
import com.example.demo.security.PasswordHashes;

public final class IsolatedFixtures {
    private static final String HASH=PasswordHashes.encoder().encode(IsolatedDatabase.ACCOUNT_PASSWORD);
    public static void reset(DataSource source) throws Exception {
        try(var connection=source.getConnection();var sql=connection.createStatement()){
            IsolatedDatabase.require(connection);
            try(var marker=sql.executeQuery("SELECT purpose FROM qa_environment_marker WHERE id=1")){
                if(!marker.next()||!marker.getString(1).equals("isolated business acceptance only"))throw new IllegalStateException("テスト識別行が必要です。");
            }
            for(String table:List.of("security_audit_log","security_source_profile","security_access_evidence","security_session_registry","security_session_subject_guard","security_login_attempt","attendance_audit_log","attendance_event","attendance_break","attendance_request","attendance_record","work_schedule","notice_read","notice","sales_data","attendance_policy_history","company_holiday"))sql.executeUpdate("DELETE FROM "+table);
            sql.executeUpdate("DELETE FROM sys_user WHERE id NOT BETWEEN 91001 AND 91007");
            sql.executeUpdate("DELETE FROM department_master WHERE id NOT BETWEEN 92001 AND 92004");
            for(int i=1;i<=7;i++){
                String role=i==1||i==7?"ADMIN":i==2?"GENERAL_AFFAIRS":"EMPLOYEE";
                String department=i==4?"QA営業一部":i==5?"QA営業二部":i==3?"QA開発部":"QA総務部";
                try(var update=connection.prepareStatement("UPDATE sys_user SET password=?,role=?,department=?,status=?,delete_flag=?,deleted_at=?,authentication_version=0 WHERE id=?")){
                    update.setString(1,HASH);update.setString(2,role);update.setString(3,department);update.setInt(4,i==6?0:1);update.setInt(5,i==6?1:0);
                    update.setTimestamp(6,i==6?new Timestamp(System.currentTimeMillis()):null);update.setLong(7,91000+i);update.executeUpdate();
                }
            }
            sql.executeUpdate("UPDATE department_master SET status=1,delete_flag=0,deleted_at=NULL,version=0,sales_enabled=(id IN(92002,92003))");
            // 打刻の通信・権限試験は実行曜日に依存させない。休息日規則は各試験で明示する。
            sql.executeUpdate("UPDATE attendance_policy SET standard_start='09:00',standard_end='18:00',break_minutes=60,late_grace_minutes=0,early_grace_minutes=0,overtime_threshold_minutes=0,work_days_mask=127,version=0");
            sql.executeUpdate("INSERT INTO attendance_policy_history(effective_from,standard_start,standard_end,break_minutes,late_grace_minutes,early_grace_minutes,overtime_threshold_minutes,work_days_mask,version,delete_flag) VALUES('1900-01-01','09:00','18:00',60,0,0,0,127,0,0)");
        }
    }
    private IsolatedFixtures(){}
}
