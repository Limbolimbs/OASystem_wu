package com.example.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.AttendancePolicy;

public interface AttendancePolicyRepository extends JpaRepository<AttendancePolicy, Long> {
}
