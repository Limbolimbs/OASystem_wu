package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.attendance.AttendanceEventType;
import com.example.demo.entity.AttendanceEvent;

public interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long> {

    Optional<AttendanceEvent>
    findFirstByAttendanceRecord_IdAndEventTypeOrderByEventAtAsc(
            Long recordId, AttendanceEventType eventType);

    List<AttendanceEvent> findByAttendanceRecord_IdOrderByCreatedAtAsc(Long recordId);
}
