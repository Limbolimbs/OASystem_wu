package com.example.demo.attendance;

public enum DayType {
    WORKDAY,
    REST_DAY,
    HOLIDAY,
    LEAVE;

    public String getLabel() {
        return switch (this) {
            case WORKDAY -> "勤務日";
            case REST_DAY -> "所定休日";
            case HOLIDAY -> "会社休日";
            case LEAVE -> "休暇";
        };
    }
}
