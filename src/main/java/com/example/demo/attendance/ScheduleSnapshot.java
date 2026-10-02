package com.example.demo.attendance;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ScheduleSnapshot(
        LocalDate workDate,
        DayType dayType,
        WorkMode workMode,
        LocalDateTime plannedStartAt,
        LocalDateTime plannedEndAt,
        int breakMinutes,
        boolean explicitlyAssigned) {
    public DayType getDayType() { return dayType; }
    public WorkMode getWorkMode() { return workMode; }
    public LocalDateTime getPlannedStartAt() { return plannedStartAt; }
    public LocalDateTime getPlannedEndAt() { return plannedEndAt; }
}
