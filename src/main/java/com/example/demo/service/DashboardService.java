package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.example.demo.entity.SalesData;
import com.example.demo.repository.SalesDataRepository;
import com.example.demo.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    private final SalesDataRepository sales;
    private final UserRepository users;

    public DashboardService(SalesDataRepository sales, UserRepository users) {
        this.sales = sales;
        this.users = users;
    }

    /** 営業実績と同じテーブルを参照する。固定値・二重集計は使用しない。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getDashboardData() {
        List<SalesData> rows = sales.findAllByOrderBySalesMonthAscDepartmentAsc();
        Map<String, BigDecimal> monthlySales = new LinkedHashMap<>();
        Map<String, Long> monthlyOrders = new LinkedHashMap<>();
        Map<String, Long> monthlyCustomers = new LinkedHashMap<>();
        Map<String, BigDecimal> departmentSales = new LinkedHashMap<>();
        BigDecimal totalSales = BigDecimal.ZERO;
        long totalOrders = 0;
        long totalCustomers = 0;
        LocalDateTime lastModified = null;

        for (SalesData row : rows) {
            monthlySales.merge(row.getSalesMonth(), row.getSalesAmount(), BigDecimal::add);
            monthlyOrders.merge(row.getSalesMonth(), row.getOrderCount().longValue(), Long::sum);
            monthlyCustomers.merge(row.getSalesMonth(), row.getCustomerCount().longValue(), Long::sum);
            departmentSales.merge(row.getDepartment(), row.getSalesAmount(), BigDecimal::add);
            totalSales = totalSales.add(row.getSalesAmount());
            totalOrders += row.getOrderCount();
            totalCustomers += row.getCustomerCount();
            if (row.getUpdatedAt() != null && (lastModified == null || row.getUpdatedAt().isAfter(lastModified))) {
                lastModified = row.getUpdatedAt();
            }
        }

        List<String> months = new ArrayList<>(monthlySales.keySet());
        BigDecimal growth = BigDecimal.ZERO;
        boolean growthAvailable = false;
        if (!months.isEmpty()) {
            String latest = months.getLast();
            String previousMonth = YearMonth.parse(latest).minusMonths(1).toString();
            BigDecimal previous = monthlySales.get(previousMonth);
            if (previous != null && previous.signum() > 0) {
                growth = monthlySales.get(latest).subtract(previous).multiply(BigDecimal.valueOf(100))
                        .divide(previous, 1, RoundingMode.HALF_UP);
                growthAvailable = true;
            }
        }

        List<Map<String, Object>> departmentData = new ArrayList<>();
        departmentSales.forEach((name, amount) -> departmentData.add(Map.of("name", name, "value", amount)));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSales", totalSales);
        summary.put("totalOrders", totalOrders);
        summary.put("totalCustomers", totalCustomers);
        summary.put("employeeCount", users.countByStatus(1));
        summary.put("salesGrowth", growth);
        summary.put("growthAvailable", growthAvailable);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("months", months);
        result.put("monthlySales", new ArrayList<>(monthlySales.values()));
        result.put("monthlyOrders", new ArrayList<>(monthlyOrders.values()));
        result.put("monthlyCustomers", new ArrayList<>(monthlyCustomers.values()));
        result.put("departmentSales", departmentData);
        result.put("lastModified", lastModified);
        result.put("recordCount", rows.size());
        return result;
    }
}
