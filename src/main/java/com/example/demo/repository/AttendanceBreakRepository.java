package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.AttendanceBreak;

public interface AttendanceBreakRepository extends JpaRepository<AttendanceBreak, Long> {

    Optional<AttendanceBreak> findFirstByAttendanceRecord_IdAndDeleteFlagAndEndedAtIsNullOrderByStartedAtDesc(Long recordId, int flag);
    default Optional<AttendanceBreak> findFirstByAttendanceRecord_IdAndEndedAtIsNullOrderByStartedAtDesc(Long recordId) {
        return findFirstByAttendanceRecord_IdAndDeleteFlagAndEndedAtIsNullOrderByStartedAtDesc(recordId, 0);
    }

    @Query("select b from AttendanceBreak b where b.attendanceRecord.id = ?1 and b.deleteFlag = 0 order by b.startedAt")
    List<AttendanceBreak> findByAttendanceRecord_IdOrderByStartedAtAsc(Long recordId);
}
