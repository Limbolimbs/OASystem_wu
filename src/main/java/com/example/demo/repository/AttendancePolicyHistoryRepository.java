package com.example.demo.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.AttendancePolicyHistory;

public interface AttendancePolicyHistoryRepository extends JpaRepository<AttendancePolicyHistory, LocalDate> {
    Optional<AttendancePolicyHistory> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(LocalDate date);
}
