package com.example.demo.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "attendance_policy_history")
public class AttendancePolicyHistory {
    @Id
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "standard_start", nullable = false)
    private LocalTime standardStart;
    @Column(name = "standard_end", nullable = false)
    private LocalTime standardEnd;
    @Column(name = "break_minutes", nullable = false)
    private int breakMinutes;
    @Column(name = "late_grace_minutes", nullable = false)
    private int lateGraceMinutes;
    @Column(name = "early_grace_minutes", nullable = false)
    private int earlyGraceMinutes;
    @Column(name = "overtime_threshold_minutes", nullable = false)
    private int overtimeThresholdMinutes;
    @Column(name = "work_days_mask", nullable = false)
    private int workDaysMask;
    @Version
    private Long version;

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate value) { effectiveFrom = value; }
    public LocalTime getStandardStart() { return standardStart; }
    public void setStandardStart(LocalTime value) { standardStart = value; }
    public LocalTime getStandardEnd() { return standardEnd; }
    public void setStandardEnd(LocalTime value) { standardEnd = value; }
    public int getBreakMinutes() { return breakMinutes; }
    public void setBreakMinutes(int value) { breakMinutes = value; }
    public int getLateGraceMinutes() { return lateGraceMinutes; }
    public void setLateGraceMinutes(int value) { lateGraceMinutes = value; }
    public int getEarlyGraceMinutes() { return earlyGraceMinutes; }
    public void setEarlyGraceMinutes(int value) { earlyGraceMinutes = value; }
    public int getOvertimeThresholdMinutes() { return overtimeThresholdMinutes; }
    public void setOvertimeThresholdMinutes(int value) { overtimeThresholdMinutes = value; }
    public int getWorkDaysMask() { return workDaysMask; }
    public void setWorkDaysMask(int value) { workDaysMask = value; }
    public Long getVersion() { return version; }
}
