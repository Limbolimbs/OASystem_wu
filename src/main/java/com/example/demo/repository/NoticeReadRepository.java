package com.example.demo.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import com.example.demo.entity.NoticeRead;
import org.springframework.data.jpa.repository.*;

public interface NoticeReadRepository extends JpaRepository<NoticeRead, Long> {
    Optional<NoticeRead> findByNotice_IdAndUser_Id(Long noticeId, Long userId);
    @Query("select r from NoticeRead r join fetch r.notice where r.user.id = :userId and r.notice.id in :ids and r.deleteFlag = 0")
    List<NoticeRead> findReadVersions(Long userId, Collection<Long> ids);
}
