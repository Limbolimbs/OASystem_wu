package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.WorkSchedule;

public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {

    /** 復元時の重複登録防止用。無効データも含む。 */
    Optional<WorkSchedule> findByUser_IdAndWorkDate(Long userId, LocalDate workDate);

    @EntityGraph(attributePaths = "user")
    @Query("select s from WorkSchedule s where s.workDate = ?1 and s.deleteFlag = 0 order by s.user.id")
    List<WorkSchedule> findByWorkDateOrderByUser_IdAsc(LocalDate workDate);
}
