package com.example.contract;

import org.springframework.stereotype.Service;

@Service
public class MyImpl implements MyInterface {

    @Override
    public void execute() {
        System.out.println("impl");
    }
}
