package com.example.demo.attendance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class AttendanceDayView {
    private final Long userId;
    private final String realName;
    private final LocalDate workDate;
    private final ScheduleSnapshot schedule;
    private final LocalDateTime clockInAt;
    private final LocalDateTime clockOutAt;
    private final long breakMinutes;
    private final long workedMinutes;
    private final List<String> statuses;

    public AttendanceDayView(Long userId, String realName, LocalDate workDate,
                             ScheduleSnapshot schedule, LocalDateTime clockInAt,
                             LocalDateTime clockOutAt, long breakMinutes,
                             long workedMinutes, List<String> statuses) {
        this.userId = userId;
        this.realName = realName;
        this.workDate = workDate;
        this.schedule = schedule;
        this.clockInAt = clockInAt;
        this.clockOutAt = clockOutAt;
        this.breakMinutes = breakMinutes;
        this.workedMinutes = workedMinutes;
        this.statuses = List.copyOf(statuses);
    }

    public Long getUserId() { return userId; }
    public String getRealName() { return realName; }
    public LocalDate getWorkDate() { return workDate; }
    public ScheduleSnapshot getSchedule() { return schedule; }
    public LocalDateTime getClockInAt() { return clockInAt; }
    public LocalDateTime getClockOutAt() { return clockOutAt; }
    public long getBreakMinutes() { return breakMinutes; }
    public long getWorkedMinutes() { return workedMinutes; }
    public List<String> getStatuses() { return statuses; }
    public String getStatusText() { return String.join(" / ", statuses); }
}
