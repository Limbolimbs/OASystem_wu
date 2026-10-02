package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import com.example.demo.entity.WorkSchedule;

public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {

    Optional<WorkSchedule> findByUser_IdAndWorkDate(Long userId, LocalDate workDate);

    @EntityGraph(attributePaths = "user")
    List<WorkSchedule> findByWorkDateOrderByUser_IdAsc(LocalDate workDate);
}
