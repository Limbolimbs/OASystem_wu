package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import com.example.demo.entity.Department;
import com.example.demo.entity.SalesData;
import com.example.demo.entity.User;
import com.example.demo.repository.SalesDataRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.sales.SalesForm;
import com.example.demo.security.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SalesServiceTest {
    @Mock SalesDataRepository repository;
    @Mock UserRepository users;
    @Mock DepartmentService departments;
    @Mock com.example.demo.security.AccountSecurityState securityState;
    SalesService service;
    User actor;
    Department department;

    @BeforeEach
    void setUp() {
        service = new SalesService(repository, departments, new AccessControlService(users, departments),users,securityState);
        actor = new User(); actor.setId(2L); actor.setRole("EMPLOYEE"); actor.setDepartment("営業部");
        lenient().when(users.findForUpdate(2L)).thenAnswer(call -> Optional.of(actor));
        department = new Department(); department.setName("営業部"); department.setSalesEnabled(true);
        lenient().when(departments.isSalesDepartment("営業部")).thenReturn(true);
        lenient().when(departments.requireActive("営業部")).thenReturn(department);
    }

    SalesForm form(Long id, Long version, String name) {
        return new SalesForm(id, version, "2026-09", name, new BigDecimal("18500000.25"), 920, 95, " 照合済み ", true);
    }

    SalesData existing() {
        SalesData row = new SalesData(); row.setId(10L); row.setVersion(3L);
        row.setDepartment("営業部"); row.setSalesMonth("2026-09"); row.setSalesAmount(BigDecimal.ONE);
        return row;
    }

    @Test
    void employeeSavesOwnDepartmentAndServerSetsEditor() {
        service.save(actor, form(null, null, "営業部"));
        ArgumentCaptor<SalesData> captured = ArgumentCaptor.forClass(SalesData.class);
        verify(repository).saveAndFlush(captured.capture());
        assertNull(captured.getValue().getId());
        assertEquals("営業部", captured.getValue().getDepartment());
        assertEquals(2L, captured.getValue().getUpdatedBy());
        assertEquals("照合済み", captured.getValue().getNotes());
        assertEquals(new BigDecimal("18500000.25"), captured.getValue().getSalesAmount());
    }

    @Test
    void employeeCannotForgeDepartment() {
        var error = assertThrows(ResponseStatusException.class, () -> service.save(actor, form(null, null, "総務部")));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void unauthorizedAndInactiveEmployeesCannotWrite() {
        actor.setDepartment("総務部");
        assertThrows(ResponseStatusException.class, () -> service.save(actor, form(null, null, "総務部")));
        actor.setDepartment("営業部"); actor.setStatus(0);
        assertThrows(ResponseStatusException.class, () -> service.save(actor, form(null, null, "営業部")));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void employeeListDoesNotReadOtherDepartments() {
        when(repository.findByDepartmentOrderBySalesMonthDescIdDesc("営業部")).thenReturn(List.of(existing()));
        assertEquals(1, service.findVisible(actor).size());
        verify(repository, never()).findAllByOrderBySalesMonthDescIdDesc();
    }

    @Test
    void forgedRecordIdCannotReadOrEditOtherDepartment() {
        SalesData row = existing(); row.setDepartment("総務部");
        when(repository.findById(10L)).thenReturn(Optional.of(row));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.findEditable(actor, 10L)).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> service.save(actor, form(10L, 3L, "営業部")));
        assertEquals(BigDecimal.ONE, row.getSalesAmount());
    }

    @Test
    void duplicateMonthIsConflictNotAddition() {
        when(repository.existsBySalesMonthAndDepartment("2026-09", "営業部")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.save(actor, form(null, null, "営業部"))).getStatusCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void matchingVersionUpdatesSameRecordAndStaleVersionIsRejected() {
        SalesData row = existing(); when(repository.findById(10L)).thenReturn(Optional.of(row));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.save(actor, form(10L, 2L, "営業部"))).getStatusCode());
        assertEquals(BigDecimal.ONE, row.getSalesAmount());
        service.save(actor, form(10L, 3L, "営業部"));
        verify(repository).saveAndFlush(row);
        assertEquals(10L, row.getId());
    }

    @Test
    void editedMonthCannotChangeIdentity() {
        SalesData row = existing(); row.setSalesMonth("2026-08");
        when(repository.findById(10L)).thenReturn(Optional.of(row));
        assertThrows(IllegalArgumentException.class, () -> service.save(actor, form(10L, 3L, "営業部")));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void invalidNumbersDatesAndUnconfirmedDataAreRejected() {
        List<SalesForm> invalid = List.of(
                new SalesForm(null, null, "2026-13", "営業部", BigDecimal.ONE, 1, 1, "", true),
                new SalesForm(null, null, "26-09", "営業部", BigDecimal.ONE, 1, 1, "", true),
                new SalesForm(null, null, "2026-09", "営業部", new BigDecimal("-1"), 1, 1, "", true),
                new SalesForm(null, null, "2026-09", "営業部", new BigDecimal("1.001"), 1, 1, "", true),
                new SalesForm(null, null, "2026-09", "営業部", BigDecimal.ONE, -1, 1, "", true),
                new SalesForm(null, null, "2026-09", "営業部", BigDecimal.ONE, 1, -1, "", true),
                new SalesForm(null, null, "2026-09", "営業部", BigDecimal.ONE, 1, 1, "長".repeat(501), true),
                new SalesForm(null, null, "2026-09", "営業部", BigDecimal.ONE, 1, 1, "", false));
        invalid.forEach(value -> assertThrows(IllegalArgumentException.class, () -> service.save(actor, value)));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void adminCanSaveNonSalesDepartmentWithoutReceivingSalesRole() {
        actor.setRole("ADMIN"); actor.setDepartment("総務部");
        Department general = new Department(); general.setName("総務部"); general.setSalesEnabled(false);
        when(departments.requireActive("総務部")).thenReturn(general);
        service.save(actor, form(null, null, "総務部"));
        verify(repository).saveAndFlush(any());
    }

    @Test
    void adminCanCorrectHistoricalRecordOfInactiveDepartment() {
        actor.setRole("ADMIN");
        SalesData row = existing();
        department.setStatus(0);
        when(departments.requireRegistered("営業部")).thenReturn(department);
        when(repository.findById(10L)).thenReturn(Optional.of(row));
        service.save(actor, form(10L, 3L, "営業部"));
        verify(repository).saveAndFlush(row);
    }
}
