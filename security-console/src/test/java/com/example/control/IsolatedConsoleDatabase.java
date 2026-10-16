package com.example.control;

import java.sql.*;
import java.util.*;
import java.security.*;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ByteArrayResource;
import static org.junit.jupiter.api.Assertions.*;

/** 限定権限も専用MySQL内で作成する。実運用設定・資格情報は読み込まない。 */
final class IsolatedConsoleDatabase {
    static final String URL="jdbc:mysql://127.0.0.1:13316/oa_qa_20261015?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Tokyo";
    static final String USER="qa_console_limited_20261016",PASSWORD="QaLimitedOnly123!";
    static boolean prepared;
    static KeyPair keys;
    static void require(Connection connection)throws SQLException{
        assertEquals("true",System.getenv("OA_ACCEPTANCE_TEST"));
        try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT @@port,@@datadir,DATABASE(),(SELECT purpose FROM qa_environment_marker WHERE id=1)")){
            assertTrue(rows.next());assertEquals(13316,rows.getInt(1));
            assertEquals("d:/oa-maintenance/business-acceptance-20261015/mysql-data/",rows.getString(2).replace('\\','/').toLowerCase(Locale.ROOT));
            assertEquals("oa_qa_20261015",rows.getString(3));assertEquals("isolated business acceptance only",rows.getString(4));
        }
    }
    static synchronized void prepare()throws Exception{
        if(prepared)return;
        assertEquals("true",System.getenv("OA_ACCEPTANCE_TEST"));
        try(var connection=DriverManager.getConnection(URL,"root","QaDatabaseOnly123!");var sql=connection.createStatement()){
            require(connection);
            sql.execute("CREATE USER IF NOT EXISTS '"+USER+"'@'localhost' IDENTIFIED BY '"+PASSWORD+"'");
            for(String table:List.of("security_console_operator","security_console_review","security_console_entry_limit","security_login_attempt","security_session_registry","security_session_subject_guard"))sql.execute("GRANT SELECT,INSERT,UPDATE ON oa_qa_20261015."+table+" TO '"+USER+"'@'localhost'");
            for(String table:List.of("security_console_action","security_access_evidence"))sql.execute("GRANT SELECT,INSERT ON oa_qa_20261015."+table+" TO '"+USER+"'@'localhost'");
            for(String table:List.of("security_audit_log","security_source_profile","qa_environment_marker"))sql.execute("GRANT SELECT ON oa_qa_20261015."+table+" TO '"+USER+"'@'localhost'");
            sql.execute("GRANT SELECT(id,username,role,status,authentication_version,delete_flag) ON oa_qa_20261015.sys_user TO '"+USER+"'@'localhost'");
        }
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);keys=generator.generateKeyPair();prepared=true;
    }
    static HikariDataSource source(int pool)throws Exception{
        prepare();var config=new HikariConfig();config.setJdbcUrl(URL);config.setUsername(USER);config.setPassword(PASSWORD);config.setMaximumPoolSize(pool);config.setConnectionInitSql("SET time_zone='+09:00'");
        var source=new HikariDataSource(config);try(var connection=source.getConnection()){require(connection);}return source;
    }
    static ConsoleEvidence evidence(JdbcTemplate jdbc,SourceGeography geography){
        return new ConsoleEvidence(jdbc,geography,Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),new ByteArrayResource(Base64.getEncoder().encode(keys.getPublic().getEncoded())));
    }
    private IsolatedConsoleDatabase(){}
}
