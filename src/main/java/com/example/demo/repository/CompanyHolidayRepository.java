package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.CompanyHoliday;

public interface CompanyHolidayRepository extends JpaRepository<CompanyHoliday, LocalDate> {
    List<CompanyHoliday> findAllByOrderByHolidayDateAsc();
}
