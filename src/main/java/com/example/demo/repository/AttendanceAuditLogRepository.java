package com.example.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.AttendanceAuditLog;

public interface AttendanceAuditLogRepository extends JpaRepository<AttendanceAuditLog, Long> {
}
