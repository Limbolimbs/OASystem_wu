package com.example.demo.attendance;

public enum WorkMode {
    OFFICE,
    FIELD,
    REMOTE;

    public String getLabel() {
        return switch (this) {
            case OFFICE -> "出社";
            case FIELD -> "外勤";
            case REMOTE -> "在宅勤務";
        };
    }
}
