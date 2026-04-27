package com.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiResult<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "success", data);
    }

    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(-1, message, null);
    }
}