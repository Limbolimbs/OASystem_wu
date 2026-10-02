package com.example.demo.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.AttendanceClock;
import com.example.demo.attendance.RequestStatus;

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
import jakarta.persistence.Version;

@Entity
@Table(name = "attendance_request")
public class AttendanceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 24)
    private AttendanceRequestType requestType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "requested_clock_in_at")
    private LocalDateTime requestedClockInAt;

    @Column(name = "requested_clock_out_at")
    private LocalDateTime requestedClockOutAt;

    @Column(name = "requested_overtime_end_at")
    private LocalDateTime requestedOvertimeEndAt;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private User reviewer;

    @Column(name = "review_comment", length = 1000)
    private String reviewComment;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

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
    public AttendanceRequestType getRequestType() { return requestType; }
    public void setRequestType(AttendanceRequestType value) { requestType = value; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate value) { startDate = value; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate value) { endDate = value; }
    public LocalDateTime getRequestedClockInAt() { return requestedClockInAt; }
    public void setRequestedClockInAt(LocalDateTime value) { requestedClockInAt = value; }
    public LocalDateTime getRequestedClockOutAt() { return requestedClockOutAt; }
    public void setRequestedClockOutAt(LocalDateTime value) { requestedClockOutAt = value; }
    public LocalDateTime getRequestedOvertimeEndAt() { return requestedOvertimeEndAt; }
    public void setRequestedOvertimeEndAt(LocalDateTime value) { requestedOvertimeEndAt = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus value) { status = value; }
    public User getReviewer() { return reviewer; }
    public void setReviewer(User value) { reviewer = value; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String value) { reviewComment = value; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime value) { reviewedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
