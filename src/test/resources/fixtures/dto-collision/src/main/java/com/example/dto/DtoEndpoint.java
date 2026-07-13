package com.example.dto;

import com.example.dto.a.Order;

public class DtoEndpoint {

    public MissingView broken(Order order) {
        return null;
    }

    public com.example.dto.b.Order duplicate(Order order) {
        return null;
    }
}
