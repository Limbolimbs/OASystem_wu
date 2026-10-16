package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.example.demo.entity.SalesData;
import com.example.demo.repository.SalesDataRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;

class DashboardServiceTest {
    SalesData row(String month, String department, String amount, int orders) {
        SalesData row = new SalesData(); row.setSalesMonth(month); row.setDepartment(department);
        row.setSalesAmount(new BigDecimal(amount)); row.setOrderCount(orders); row.setCustomerCount(5);
        return row;
    }

    @Test void aggregatesDatabaseRowsAndRereadsUpdatedValuesWithoutFixedData() {
        SalesDataRepository sales = mock(SalesDataRepository.class); UserRepository users = mock(UserRepository.class);
        SalesData first = row("2026-08", "営業部", "100", Integer.MAX_VALUE);
        SalesData second = row("2026-09", "営業部", "200", 10);
        when(sales.findAllByOrderBySalesMonthAscDepartmentAsc()).thenReturn(List.of(first, second));
        when(users.countByStatus(1)).thenReturn(3L);
        DashboardService service = new DashboardService(sales, users);
        Map<?, ?> summary = (Map<?, ?>) service.getDashboardData().get("summary");
        assertEquals(new BigDecimal("300"), summary.get("totalSales"));
        assertEquals(2147483657L, summary.get("totalOrders"));
        assertEquals(new BigDecimal("100.0"), summary.get("salesGrowth"));
        second.setSalesAmount(new BigDecimal("250"));
        summary = (Map<?, ?>) service.getDashboardData().get("summary");
        assertEquals(new BigDecimal("350"), summary.get("totalSales"));
        verify(sales, times(2)).findAllByOrderBySalesMonthAscDepartmentAsc();
    }

    @Test void missingCalendarPreviousMonthDoesNotInventMonthOverMonthComparison() {
        SalesDataRepository sales = mock(SalesDataRepository.class); UserRepository users = mock(UserRepository.class);
        when(sales.findAllByOrderBySalesMonthAscDepartmentAsc()).thenReturn(
                List.of(row("2026-07", "営業部", "100", 1), row("2026-09", "営業部", "200", 1)));
        Map<?, ?> summary = (Map<?, ?>) new DashboardService(sales, users).getDashboardData().get("summary");
        assertEquals(false, summary.get("growthAvailable"));
    }

    @Test void emptyDatabaseReturnsZeroAndEmptyCharts() {
        SalesDataRepository sales = mock(SalesDataRepository.class); UserRepository users = mock(UserRepository.class);
        when(sales.findAllByOrderBySalesMonthAscDepartmentAsc()).thenReturn(List.of());
        var data = new DashboardService(sales, users).getDashboardData();
        assertEquals(List.of(), data.get("months"));
        assertEquals(BigDecimal.ZERO, ((Map<?, ?>) data.get("summary")).get("totalSales"));
    }
}
