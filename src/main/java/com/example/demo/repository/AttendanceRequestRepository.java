package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.RequestStatus;
import com.example.demo.entity.AttendanceRequest;

public interface AttendanceRequestRepository extends JpaRepository<AttendanceRequest, Long> {

    @EntityGraph(attributePaths = {"user", "reviewer"})
    Page<AttendanceRequest> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewer"})
    Page<AttendanceRequest> findByStatusOrderByCreatedAtAsc(RequestStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewer"})
    Page<AttendanceRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"user", "reviewer"})
    Page<AttendanceRequest> findByReviewer_IdOrderByReviewedAtDesc(Long reviewerId, Pageable pageable);

    List<AttendanceRequest>
    findByUser_IdAndRequestTypeAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long userId, AttendanceRequestType requestType, RequestStatus status,
            LocalDate endDate, LocalDate startDate);
}
