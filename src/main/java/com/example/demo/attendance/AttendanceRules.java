package com.example.demo.attendance;

import java.time.LocalTime;

public record AttendanceRules(
        LocalTime standardStart,
        LocalTime standardEnd,
        int breakMinutes,
        int lateGraceMinutes,
        int earlyGraceMinutes,
        int overtimeThresholdMinutes,
        int workDaysMask) {
}
