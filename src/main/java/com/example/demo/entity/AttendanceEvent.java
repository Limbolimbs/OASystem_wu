package com.example.demo.entity;

import java.time.LocalDateTime;

import com.example.demo.attendance.AttendanceEventType;
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
import jakarta.persistence.Table;

@Entity
@Table(name = "attendance_event")
public class AttendanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attendance_record_id", nullable = false)
    private AttendanceRecord attendanceRecord;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private AttendanceEventType eventType;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", length = 20)
    private WorkMode workMode;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = AttendanceClock.now();

    public Long getId() { return id; }
    public AttendanceRecord getAttendanceRecord() { return attendanceRecord; }
    public void setAttendanceRecord(AttendanceRecord value) { attendanceRecord = value; }
    public User getUser() { return user; }
    public void setUser(User value) { user = value; }
    public User getActor() { return actor; }
    public void setActor(User value) { actor = value; }
    public AttendanceEventType getEventType() { return eventType; }
    public void setEventType(AttendanceEventType value) { eventType = value; }
    public LocalDateTime getEventAt() { return eventAt; }
    public void setEventAt(LocalDateTime value) { eventAt = value; }
    public WorkMode getWorkMode() { return workMode; }
    public void setWorkMode(WorkMode value) { workMode = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
