package com.example.demo.attendance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 勤怠日時は日本時間で統一する。 */
public final class AttendanceClock {
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private AttendanceClock() { }

    public static LocalDateTime now() { return LocalDateTime.now(ZONE); }
    public static LocalDate today() { return LocalDate.now(ZONE); }
}
