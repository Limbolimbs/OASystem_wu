package com.example.demo.entity;

import java.time.LocalDateTime;
import com.example.demo.attendance.AttendanceClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 既存の所属名と互換性を保つ部署マスタ。部署名は登録後に変更しない。 */
@Entity
@Table(name = "department_master")
public class Department extends LogicalDeleteEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 50)
    private String code;
    @Column(nullable = false, unique = true)
    private String name;
    @Column(name = "sales_enabled", nullable = false)
    private boolean salesEnabled;
    @Column(nullable = false)
    private Integer status = 1;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    @PrePersist
    void create() { createdAt = AttendanceClock.now(); updatedAt = createdAt; }
    @PreUpdate
    void update() { updatedAt = AttendanceClock.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isSalesEnabled() { return salesEnabled; }
    public void setSalesEnabled(boolean salesEnabled) { this.salesEnabled = salesEnabled; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) {
        this.status = status;
        if (Integer.valueOf(0).equals(status)) super.softDelete();
        else if (Integer.valueOf(1).equals(status)) super.restore();
    }
    @Override
    public void softDelete() { super.softDelete(); status = 0; }
    @Override
    public void restore() { super.restore(); status = 1; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
