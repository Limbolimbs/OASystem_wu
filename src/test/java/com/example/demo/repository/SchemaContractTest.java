package com.example.demo.repository;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import com.example.demo.entity.*;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

/** 移行SQLとJava側の全テーブル共通列・日時型の整合を検査する。 */
class SchemaContractTest {
    @Test void migrationCoversAllTwelveTablesAndEveryMappedTimestamp() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/database/05_datetime_logical_delete.sql"));
        for (Class<?> type : List.of(User.class, Department.class, SalesData.class, AttendanceRecord.class,
                AttendancePolicy.class, AttendancePolicyHistory.class, CompanyHoliday.class,
                WorkSchedule.class, AttendanceRequest.class, AttendanceBreak.class,
                AttendanceEvent.class, AttendanceAuditLog.class)) {
            String table = type.getAnnotation(Table.class).name();
            var match = Pattern.compile("ALTER TABLE " + table + "\\s+(.*?);", Pattern.DOTALL).matcher(sql);
            assertTrue(match.find(), table);
            String definition = match.group(1);
            assertTrue(definition.contains("ADD COLUMN delete_flag INT NOT NULL DEFAULT 0"), table);
            assertTrue(definition.contains("COLUMN deleted_at DATETIME(6) NULL"), table);
            assertTrue(definition.contains("CHECK (delete_flag IN (0, 1))"), table);
            assertTrue(LogicalDeleteEntity.class.isAssignableFrom(type), table);
            for (var field : type.getDeclaredFields()) {
                if (field.getType() != LocalDateTime.class) continue;
                Column column = field.getAnnotation(Column.class);
                assertNotNull(column, field.getName());
                assertEquals("DATETIME(6)", column.columnDefinition(), table + "." + field.getName());
                assertTrue(definition.contains("MODIFY COLUMN " + column.name() + " DATETIME(6)"), table + "." + column.name());
            }
        }
    }

    @Test void migrationNeverPhysicallyDeletesDataAndPreservesExistingUniqueKeys() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/database/05_datetime_logical_delete.sql"));
        String statements = sql.replaceAll("(?m)^--.*$", "");
        assertFalse(Pattern.compile("\\b(DELETE\\s+FROM|DROP\\s+|TRUNCATE\\s+)", Pattern.CASE_INSENSITIVE).matcher(statements).find());
        assertTrue(sql.contains("WHERE status = 0"));
        assertTrue(sql.contains("updated_at = updated_at"));
        assertTrue(sql.contains("SET SESSION time_zone = '+09:00'"));
    }
}
