package com.example.demo.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.example.demo.attendance.DayType;
import com.example.demo.attendance.AttendanceClock;
import com.example.demo.attendance.WorkMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "work_schedule", uniqueConstraints = {
        @UniqueConstraint(name = "uk_work_schedule_user_date", columnNames = {"user_id", "work_date"})
})
public class WorkSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "planned_start_at")
    private LocalDateTime plannedStartAt;

    @Column(name = "planned_end_at")
    private LocalDateTime plannedEndAt;

    @Column(name = "break_minutes", nullable = false)
    private int breakMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private DayType dayType;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 20)
    private WorkMode workMode = WorkMode.OFFICE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        createdAt = AttendanceClock.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = AttendanceClock.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User value) { user = value; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate value) { workDate = value; }
    public LocalDateTime getPlannedStartAt() { return plannedStartAt; }
    public void setPlannedStartAt(LocalDateTime value) { plannedStartAt = value; }
    public LocalDateTime getPlannedEndAt() { return plannedEndAt; }
    public void setPlannedEndAt(LocalDateTime value) { plannedEndAt = value; }
    public int getBreakMinutes() { return breakMinutes; }
    public void setBreakMinutes(int value) { breakMinutes = value; }
    public DayType getDayType() { return dayType; }
    public void setDayType(DayType value) { dayType = value; }
    public WorkMode getWorkMode() { return workMode; }
    public void setWorkMode(WorkMode value) { workMode = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
