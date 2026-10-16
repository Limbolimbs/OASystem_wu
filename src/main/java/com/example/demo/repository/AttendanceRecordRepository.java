package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.AttendanceRecord;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    /** 復元・二重打刻防止用。無効データも含むため、表示時は isDeleted を確認する。 */
    Optional<AttendanceRecord> findByUser_IdAndWorkDate(Long userId, LocalDate workDate);

    Optional<AttendanceRecord> findFirstByUser_IdAndDeleteFlagAndClockOutAtIsNullOrderByClockInAtDesc(Long userId, int flag);
    default Optional<AttendanceRecord> findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(Long userId) {
        return findFirstByUser_IdAndDeleteFlagAndClockOutAtIsNullOrderByClockInAtDesc(userId, 0);
    }

    List<AttendanceRecord> findTop30ByUser_IdAndDeleteFlagOrderByWorkDateDesc(Long userId, int flag);
    default List<AttendanceRecord> findTop30ByUser_IdOrderByWorkDateDesc(Long userId) {
        return findTop30ByUser_IdAndDeleteFlagOrderByWorkDateDesc(userId, 0);
    }

    @EntityGraph(attributePaths = "user")
    @Query("select r from AttendanceRecord r where r.workDate = ?1 and r.deleteFlag = 0 order by r.user.id")
    List<AttendanceRecord> findByWorkDateOrderByUser_IdAsc(LocalDate workDate);
}
