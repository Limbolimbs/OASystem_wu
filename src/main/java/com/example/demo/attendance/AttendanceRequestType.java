package com.example.demo.attendance;

public enum AttendanceRequestType {
    CORRECTION,
    FIELD,
    REMOTE,
    OVERTIME,
    HOLIDAY_WORK,
    LEAVE;

    public String getLabel() {
        return switch (this) {
            case CORRECTION -> "打刻修正";
            case FIELD -> "外勤";
            case REMOTE -> "在宅勤務";
            case OVERTIME -> "残業";
            case HOLIDAY_WORK -> "休日出勤";
            case LEAVE -> "休暇";
        };
    }
}
