package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.CompanyHoliday;

public interface CompanyHolidayRepository extends JpaRepository<CompanyHoliday, LocalDate> {
    @Query("select h from CompanyHoliday h where h.deleteFlag = 0 order by h.holidayDate")
    List<CompanyHoliday> findAllByOrderByHolidayDateAsc();
    @Override
    @Query("select (count(h) > 0) from CompanyHoliday h where h.holidayDate = ?1 and h.deleteFlag = 0")
    boolean existsById(LocalDate date);
}
