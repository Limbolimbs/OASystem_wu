package com.example.demo.entity;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.List;
import com.example.demo.attendance.AttendanceClock;
import org.junit.jupiter.api.Test;

class LogicalDeleteEntityTest {
    @Test void everyBusinessTableSupportsSoftDeleteAndRestoreWithoutChangingItsIdentity() {
        List<LogicalDeleteEntity> entities = List.of(new User(), new Department(), new SalesData(),
                new AttendanceRecord(), new AttendancePolicy(), new AttendancePolicyHistory(),
                new CompanyHoliday(), new WorkSchedule(), new AttendanceRequest(), new AttendanceBreak(),
                new AttendanceEvent(), new AttendanceAuditLog());
        for (LogicalDeleteEntity value : entities) {
            assertEquals(0, value.getDeleteFlag());
            assertNull(value.getDeletedAt());
            value.softDelete();
            assertTrue(value.isDeleted());
            assertEquals(1, value.getDeleteFlag());
            LocalDateTime deletedAt = value.getDeletedAt();
            assertNotNull(deletedAt);
            assertEquals(0, deletedAt.getNano() % 1000);
            value.softDelete();
            assertEquals(deletedAt, value.getDeletedAt());
            value.restore();
            assertFalse(value.isDeleted());
            assertNull(value.getDeletedAt());
        }
    }

    @Test void employeeAndDepartmentStatusStaySynchronized() {
        User user = new User(); user.setId(5L); user.setStatus(0);
        assertEquals(1, user.getDeleteFlag()); assertNotNull(user.getDeletedAt());
        user.restore(); assertEquals(1, user.getStatus()); assertEquals(5L, user.getId());
        user.softDelete(); assertEquals(0, user.getStatus());
        user.setStatus(1); assertEquals(0, user.getDeleteFlag()); assertNull(user.getDeletedAt());
        Department department = new Department(); department.setStatus(0);
        assertTrue(department.isDeleted()); department.setStatus(1);
        assertFalse(department.isDeleted()); assertNull(department.getDeletedAt());
        department.softDelete(); assertEquals(0, department.getStatus());
    }

    @Test void currentTimestampMatchesMysqlMicrosecondPrecision() {
        assertEquals(0, AttendanceClock.now().getNano() % 1000);
        assertEquals(AttendanceClock.today(), AttendanceClock.now().toLocalDate());
    }

    @Test void entityManagerPhysicalRemovalCallbackIsRejected() {
        assertThrows(IllegalStateException.class, () -> new User().rejectPhysicalDelete());
        assertThrows(IllegalStateException.class, () -> new SalesData().rejectPhysicalDelete());
    }
}
