package com.example.demo.entity;

import java.time.LocalDateTime;
import com.example.demo.attendance.AttendanceClock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "attendance_audit_log")
public class AttendanceAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actor;

    @Column(name = "action_name", nullable = false, length = 50)
    private String actionName;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = AttendanceClock.now();

    public Long getId() { return id; }
    public User getActor() { return actor; }
    public void setActor(User value) { actor = value; }
    public String getActionName() { return actionName; }
    public void setActionName(String value) { actionName = value; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String value) { targetType = value; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String value) { targetId = value; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String value) { oldValue = value; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String value) { newValue = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
