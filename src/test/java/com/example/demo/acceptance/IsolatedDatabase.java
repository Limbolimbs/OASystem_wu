package com.example.demo.acceptance;

import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本機の専用テストインスタンス以外に書き込まない。実クラウド資格情報は参照しない。 */
public final class IsolatedDatabase {
    public static final String NAME="oa_qa_20261015";
    public static final String URL="jdbc:mysql://127.0.0.1:13316/"+NAME+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Tokyo";
    public static final String PASSWORD="QaDatabaseOnly123!";
    public static final String ACCOUNT_PASSWORD="QaDemoPass123!";
    public static void require(Connection connection) throws Exception {
        assertEquals("true",System.getenv("OA_ACCEPTANCE_TEST"),"明示的な隔離試験指定が必要です。");
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT @@port,@@datadir,DATABASE()")){
            assertTrue(result.next());assertEquals(13316,result.getInt(1));
            assertEquals("d:/oa-maintenance/business-acceptance-20261015/mysql-data/",result.getString(2).replace('\\','/').toLowerCase());
            assertTrue(result.getString(3)==null||NAME.equals(result.getString(3)));
        }
    }
    public static Connection connection() throws Exception {
        var connection=DriverManager.getConnection(URL,"root",PASSWORD);require(connection);return connection;
    }
    private IsolatedDatabase(){}
}
