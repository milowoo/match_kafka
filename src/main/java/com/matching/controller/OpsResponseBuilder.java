package com.matching.controller;

import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维接口统一响应构建器
 */
public final class OpsResponseBuilder {

    private OpsResponseBuilder() {}

    public static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        data.put("status", "success");
        data.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(data);
    }

    public static ResponseEntity<Map<String, Object>> ok(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "success");
        data.put("message", message);
        data.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(data);
    }

    public static ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "error");
        data.put("message", message);
        data.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(500).body(data);
    }

    public static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "error");
        data.put("message", message);
        data.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(400).body(data);
    }

    public static Map<String, Object> map() { return new LinkedHashMap<>(); }
}