package com.matching.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;

/**
 * Unified JSON utility with Jackson + Blackbird, Blackbird uses MethodHandle to optimize ser/deser for JDK
 * 17x ~ 2.3x faster than vanilla Jackson. Zero code change required - same ObjectMapper API
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.registerModule(new BlackbirdModule());
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    private JsonUtil() {}

    public static <T> T deserialize(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialize failed", e);
        }
    }

    public static String serialize(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }

    public static ObjectMapper jackson() {
        return MAPPER;
    }
}