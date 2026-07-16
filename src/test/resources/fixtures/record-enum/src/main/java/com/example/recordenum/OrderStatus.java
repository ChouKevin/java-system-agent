package com.example.recordenum;

public enum OrderStatus {
    NEW,
    PAID;

    public boolean isFinal() {
        return this == PAID;
    }
}
