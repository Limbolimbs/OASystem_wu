package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Optional;
import com.example.demo.entity.Department;
import com.example.demo.entity.User;
import com.example.demo.repository.DepartmentRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceTest {
    @Mock DepartmentRepository repository;
    @Mock UserRepository users;
    DepartmentService service;
    @BeforeEach void setUp() { service = new DepartmentService(repository, users); }

    @Test void createsValidDepartmentButRejectsDuplicateAndInvalidCode() {
        service.create(" SALES ", " 営業部 ", true);
        verify(repository).saveAndFlush(any());
        when(repository.existsByCodeOrName("SALES", "営業部")).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> service.create("SALES", "営業部", true));
        assertThrows(IllegalArgumentException.class, () -> service.create("sales", "営業部", false));
    }

    @Test void doesNotDisableOccupiedDepartmentOrIgnoreVersion() {
        Department row = new Department(); row.setName("営業部"); row.setVersion(2L);
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        when(users.countByDepartmentAndStatus("営業部", 1)).thenReturn(3L);
        assertThrows(IllegalArgumentException.class, () -> service.update(1L, 2L, true, 0));
        assertThrows(ResponseStatusException.class, () -> service.update(1L, 1L, true, 1));
        verify(repository, never()).flush();
        assertEquals(1, row.getStatus());
    }

    @Test void updatesSettingsWithoutDeletingHistoricalDepartment() {
        Department row = new Department(); row.setName("営業部"); row.setVersion(0L);
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        service.update(1L, 0L, false, 0);
        assertEquals(0, row.getStatus()); assertFalse(row.isSalesEnabled());
        verify(repository).flush(); verify(repository, never()).delete(any());
    }

    @Test void employeeAssignmentAcceptsOnlyActiveMaster() {
        Department row = new Department(); row.setName("営業部");
        User user = new User(); user.setDepartment("総務部");
        when(repository.findByNameAndStatus("営業部", 1)).thenReturn(Optional.of(row));
        when(users.findById(2L)).thenReturn(Optional.of(user));
        service.assignEmployee(2L, "営業部");
        assertEquals("営業部", user.getDepartment()); verify(users).flush();
        assertThrows(IllegalArgumentException.class, () -> service.assignEmployee(2L, "未登録部署"));
    }

    @Test void salesAccessRequiresActiveDepartmentNotOnlyName() {
        when(repository.findByNameAndStatus("営業部", 1)).thenReturn(Optional.empty());
        assertFalse(service.isSalesDepartment("営業部")); assertFalse(service.isSalesDepartment(null));
        Department row = new Department(); row.setSalesEnabled(true);
        when(repository.findByNameAndStatus("営業部", 1)).thenReturn(Optional.of(row));
        assertTrue(service.isSalesDepartment("営業部"));
    }
}
