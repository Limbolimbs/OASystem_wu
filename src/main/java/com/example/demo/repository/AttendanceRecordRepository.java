package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.AttendanceRecord;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findByUser_IdAndWorkDate(Long userId, LocalDate workDate);

    Optional<AttendanceRecord> findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(Long userId);

    List<AttendanceRecord> findTop30ByUser_IdOrderByWorkDateDesc(Long userId);

    @EntityGraph(attributePaths = "user")
    List<AttendanceRecord> findByWorkDateOrderByUser_IdAsc(LocalDate workDate);
}
