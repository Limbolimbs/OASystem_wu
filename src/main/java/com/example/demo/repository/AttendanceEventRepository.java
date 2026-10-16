package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.attendance.AttendanceEventType;
import com.example.demo.entity.AttendanceEvent;

public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {

    Optional<AttendanceEvent> findFirstByAttendanceRecord_IdAndEventTypeAndDeleteFlagOrderByEventAtAsc(
            Long recordId, AttendanceEventType eventType, int flag);
    default Optional<AttendanceEvent> findFirstByAttendanceRecord_IdAndEventTypeOrderByEventAtAsc(
            Long recordId, AttendanceEventType eventType) {
        return findFirstByAttendanceRecord_IdAndEventTypeAndDeleteFlagOrderByEventAtAsc(recordId, eventType, 0);
    }

    @Query("select e from AttendanceEvent e where e.attendanceRecord.id = ?1 and e.deleteFlag = 0 order by e.createdAt")
    List<AttendanceEvent> findByAttendanceRecord_IdOrderByCreatedAtAsc(Long recordId);
}
