package com.example.demo.entity;

import java.time.LocalDateTime;
import com.example.demo.attendance.AttendanceClock;
import jakarta.persistence.*;

@Entity
@Table(name = "notice_read", uniqueConstraints = @UniqueConstraint(name = "uk_notice_read_user", columnNames = {"notice_id", "user_id"}))
public class NoticeRead extends LogicalDeleteEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "read_version", nullable = false)
    private Long readVersion;
    @Column(name = "read_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime readAt;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;
    @Version @Column(nullable = false)
    private Long version;
    @PrePersist void createTime() { createdAt = AttendanceClock.now(); updatedAt = createdAt; }
    @PreUpdate void updateTime() { updatedAt = AttendanceClock.now(); }
    public void markRead(Notice value, User actor) {
        restore(); notice = value; user = actor; readVersion = value.getVersion(); readAt = AttendanceClock.now();
    }
    public Notice getNotice() { return notice; }
    public Long getReadVersion() { return readVersion; }
}
