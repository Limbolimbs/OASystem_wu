package com.example.demo.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.example.demo.attendance.AttendanceClock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_holiday")
public class CompanyHoliday {

    @Id
    @Column(name = "holiday_date")
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = AttendanceClock.now();

    public LocalDate getHolidayDate() { return holidayDate; }
    public void setHolidayDate(LocalDate value) { holidayDate = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
