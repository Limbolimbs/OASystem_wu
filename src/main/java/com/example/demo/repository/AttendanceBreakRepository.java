package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.AttendanceBreak;

public interface AttendanceBreakRepository extends JpaRepository<AttendanceBreak, Long> {

    Optional<AttendanceBreak>
    findFirstByAttendanceRecord_IdAndEndedAtIsNullOrderByStartedAtDesc(Long recordId);

    List<AttendanceBreak> findByAttendanceRecord_IdOrderByStartedAtAsc(Long recordId);
}
