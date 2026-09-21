package com.example.migration.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 误差椭圆坐标系与轨迹坐标系不一致时，禁止距离判定。 */
@ResponseStatus(HttpStatus.CONFLICT)
public class CrsMismatchException extends RuntimeException {

    public CrsMismatchException(String message) {
        super(message);
    }
}
