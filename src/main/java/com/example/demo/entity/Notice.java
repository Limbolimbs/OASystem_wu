package com.example.demo.entity;

import java.time.LocalDateTime;
import com.example.demo.attendance.AttendanceClock;
import com.example.demo.notice.NoticePriority;
import com.example.demo.notice.NoticeStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "notice")
public class Notice extends LogicalDeleteEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String title;
    @Column(nullable = false, length = 10000)
    private String content;
    @Column(nullable = false, length = 30)
    private String category;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private NoticePriority priority = NoticePriority.NORMAL;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private NoticeStatus status = NoticeStatus.DRAFT;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "updated_by", nullable = false)
    private User updatedBy;
    @Column(name = "published_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime publishedAt;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;
    @Version @Column(nullable = false)
    private Long version;

    @PrePersist void createTime() { createdAt = AttendanceClock.now(); updatedAt = createdAt; }
    @PreUpdate void updateTime() { updatedAt = AttendanceClock.now(); }
    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    public NoticePriority getPriority() { return priority; }
    public void setPriority(NoticePriority value) { priority = value; }
    public NoticeStatus getStatus() { return status; }
    public void setStatus(NoticeStatus value) { status = value; }
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User value) { createdBy = value; }
    public User getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(User value) { updatedBy = value; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime value) { publishedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
