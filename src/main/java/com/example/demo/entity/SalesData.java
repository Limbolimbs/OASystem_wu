package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.example.demo.attendance.AttendanceClock;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
@Table(name = "sales_data", uniqueConstraints = @UniqueConstraint(name = "uk_sales_month_department", columnNames = {"sales_month", "department"}))
public class SalesData extends LogicalDeleteEntity {
	
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

    @Column(length = 500)
    private String notes;
    @Column(name = "updated_by")
    private Long updatedBy;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;
    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    void create() { createdAt = AttendanceClock.now(); updatedAt = createdAt; }
    @PreUpdate
    void update() { updatedAt = AttendanceClock.now(); }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    
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
