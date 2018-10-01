package com.josh.demo.service.impl;

import com.josh.demo.service.IDemoService;
import com.josh.mvcframework.annotation.GPService;

/**
 * Created by sulin on 2018/9/30.
 */
@GPService
public class DemoService implements IDemoService {
    @Override
    public String get(String name) {
        return "My name is " + name;
    }
}
