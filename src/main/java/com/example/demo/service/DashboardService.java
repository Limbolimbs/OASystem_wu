package com.example.demo.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.demo.entity.SalesData;
import com.example.demo.repository.SalesDataRepository;
import com.example.demo.repository.UserRepository;

@Service
public class DashboardService {
    private final SalesDataRepository salesDataRepository;
    private final UserRepository userRepository;
	public DashboardService(SalesDataRepository salesDataRepository, UserRepository userRepository) {
		super();
		this.salesDataRepository = salesDataRepository;
		this.userRepository = userRepository;
	}
    
	public Map<String,Object>getDashboardData(){
		
		List<SalesData> rows = salesDataRepository.findAllByOrderBySalesMonthAscDepartmentAsc();
		
		Map<String,BigDecimal> salesByMonth = new LinkedHashMap<>();
		
		Map<String,Integer> ordersByMonth = new LinkedHashMap<>();
		
		Map<String,Integer> customersByMonth = new LinkedHashMap<>();
		
		Map<String,BigDecimal> salesByDepartment = new LinkedHashMap<>();
		
		BigDecimal totalSales  = BigDecimal.ZERO;
		int totalOrders = 0;
		int totalCustomers = 0;
		
		for(SalesData row : rows) {
			
			salesByMonth.merge(row.getSalesMonth(), row.getSalesAmount(), BigDecimal::add);
			
			ordersByMonth.merge(row.getSalesMonth(), row.getOrderCount(), Integer::sum);
			
			customersByMonth.merge(row.getSalesMonth(), row.getCustomerCount(), Integer::sum);
			
			salesByDepartment.merge(row.getDepartment(), row.getSalesAmount(), BigDecimal::add);
			
			totalSales = totalSales.add(row.getSalesAmount());
			
			totalOrders += row.getOrderCount();
			
			totalCustomers += row.getCustomerCount();
		}
		
		List<BigDecimal> monthlySales = new ArrayList<>(salesByMonth.values());
		
		double salesGrowth = 0;
		
		if(monthlySales.size() >= 2) {
			
			BigDecimal current = monthlySales.get(monthlySales.size() -1);
			
			BigDecimal previous = monthlySales.get(monthlySales.size() -2);
			
			if(previous.compareTo(BigDecimal.ZERO) > 0) {
				salesGrowth = current.subtract(previous).doubleValue() / previous.doubleValue() * 100;
			}
		}
		
		List<Map<String,Object>> departmentData = new ArrayList<>();
		
		salesByDepartment.forEach((department,amount) -> {
			
			Map<String,Object> item = new LinkedHashMap<>();
			
			item.put("name", department);
			item.put("value",amount);
			
			departmentData.add(item);
			
		});
		
		Map<String,Object> summary = new LinkedHashMap<>();
		
		summary.put("totalSales", totalSales);
		summary.put("totalOrders", totalOrders);
		summary.put("totalCustomers", totalCustomers);
		
		// 有効ユーザー数
		summary.put("employeeCount", userRepository.countByStatus(1));
		
		summary.put("salesGrowth", Math.round(salesGrowth * 10.0) / 10.0);
		
		Map<String,Object> result = new LinkedHashMap<>();
		
		result.put("summary", summary);
		
		result.put("months", new ArrayList<>(salesByMonth.keySet()));
		
		result.put("monthlySales", monthlySales);
		
		result.put("monthlyOrders", new ArrayList<>(ordersByMonth.values()));
		
		result.put("monthlyCustomers", new ArrayList<>(customersByMonth.values()));
		
		result.put("departmentSales", departmentData);
		
		return result;
		
	}
    
}
