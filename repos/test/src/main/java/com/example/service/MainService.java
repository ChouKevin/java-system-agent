package com.example.service;

import com.example.contract.MyImpl;
import com.example.contract.MyInterface;

public class MainService {

    private final HelperService helperService = new HelperService();
    private final MyInterface myInterface = new MyImpl();

    public void entryPoint() {
        helperService.doSomething();
        myInterface.execute();
        recursiveMethod(0);
    }

    private void recursiveMethod(int depth) {
        if (depth < 2) {
            recursiveMethod(depth + 1);
        }
    }
}
