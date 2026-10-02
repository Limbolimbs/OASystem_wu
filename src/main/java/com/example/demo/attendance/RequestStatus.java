package com.example.demo.attendance;

public enum RequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED;

    public String getLabel() {
        return switch (this) {
            case PENDING -> "審査中";
            case APPROVED -> "承認済み";
            case REJECTED -> "差し戻し";
            case CANCELLED -> "取り下げ";
        };
    }
}
