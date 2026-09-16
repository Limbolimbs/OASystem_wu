package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
@Table(name = "sales_data")
public class SalesData {
	
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "sales_month",nullable = false)
    private String salesMonth;
    
    @Column(nullable = false)
    private String department;
    
    @Column(name = "sales_amount",nullable = false)
    private BigDecimal salesAmount;
    
    @Column(name = "order_count",nullable = false)
    private Integer orderCount;
    
    @Column(name = "customer_count",nullable = false)
    private Integer customerCount;
    
    public SalesData() {
    }

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getSalesMonth() {
		return salesMonth;
	}

	public void setSalesMonth(String salesMonth) {
		this.salesMonth = salesMonth;
	}

	public String getDepartment() {
		return department;
	}

	public void setDepartment(String department) {
		this.department = department;
	}

	public BigDecimal getSalesAmount() {
		return salesAmount;
	}

	public void setSalesAmount(BigDecimal salesAmount) {
		this.salesAmount = salesAmount;
	}

	public Integer getOrderCount() {
		return orderCount;
	}

	public void setOrderCount(Integer orderCount) {
		this.orderCount = orderCount;
	}

	public Integer getCustomerCount() {
		return customerCount;
	}

	public void setCustomerCount(Integer customerCount) {
		this.customerCount = customerCount;
	}
    
    
}
