package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.RequestStatus;
import com.example.demo.entity.AttendanceRequest;

public interface AttendanceRequestRepository extends JpaRepository<AttendanceRequest, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AttendanceRequest r where r.id = ?1")
    Optional<AttendanceRequest> findForUpdate(Long id);

    @EntityGraph(attributePaths = {"user", "reviewer"})
    @Query("select r from AttendanceRequest r where r.user.id = ?1 and r.deleteFlag = 0 order by r.createdAt desc")
    Page<AttendanceRequest> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewer"})
    @Query("select r from AttendanceRequest r where r.status = ?1 and r.deleteFlag = 0 order by r.createdAt")
    Page<AttendanceRequest> findByStatusOrderByCreatedAtAsc(RequestStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewer"})
    @Query("select r from AttendanceRequest r where r.deleteFlag = 0 order by r.createdAt desc")
    Page<AttendanceRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewer"})
    @Query("select r from AttendanceRequest r where r.reviewer.id = ?1 and r.deleteFlag = 0 order by r.reviewedAt desc")
    Page<AttendanceRequest> findByReviewer_IdOrderByReviewedAtDesc(Long reviewerId, Pageable pageable);

    @Query("select r from AttendanceRequest r where r.user.id = ?1 and r.requestType = ?2 and r.status = ?3 and r.startDate <= ?4 and r.endDate >= ?5 and r.deleteFlag = 0")
    List<AttendanceRequest>
    findByUser_IdAndRequestTypeAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long userId, AttendanceRequestType requestType, RequestStatus status,
            LocalDate endDate, LocalDate startDate);
}
