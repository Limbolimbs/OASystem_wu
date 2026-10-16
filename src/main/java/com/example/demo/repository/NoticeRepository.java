package com.example.demo.repository;

import java.util.Optional;
import com.example.demo.entity.Notice;
import com.example.demo.notice.NoticeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface NoticeRepository extends JpaRepository<Notice, Long> {
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    @Query("select n from Notice n where n.deleteFlag = :deleted and (:manager = true or n.status = com.example.demo.notice.NoticeStatus.PUBLISHED)"
        + " and (:status is null or n.status = :status) and (lower(n.title) like lower(:pattern) escape '!' or lower(n.content) like lower(:pattern) escape '!')"
        + " order by case when n.priority = com.example.demo.notice.NoticePriority.IMPORTANT then 0 else 1 end, coalesce(n.publishedAt,n.updatedAt) desc,n.id desc")
    Page<Notice> search(boolean manager, int deleted, NoticeStatus status, String pattern, Pageable pageable);

    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    @Query("select n from Notice n where n.id = :id")
    Optional<Notice> detail(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notice n where n.id = :id")
    Optional<Notice> findForUpdate(Long id);

    @Query("select count(n) from Notice n where n.deleteFlag = 0 and n.status = com.example.demo.notice.NoticeStatus.PUBLISHED"
        + " and not exists (select r.id from NoticeRead r where r.notice.id = n.id and r.user.id = :userId and r.deleteFlag = 0 and r.readVersion = n.version)")
    long unreadCount(Long userId);
}
