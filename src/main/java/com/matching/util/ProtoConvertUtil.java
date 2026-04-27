package com.matching.util;

import java.math.BigDecimal;

/**
 * Proto 转换共享工具方法
 */
final class ProtoConvertUtil {

    private ProtoConvertUtil() {}

    /**
     * BigDecimal 转 String（空值返回空字符串）
     */
    static String bd(BigDecimal v) {
        return v != null ? v.toPlainString() : "";
    }

    /**
     * String 校验（空值返回空字符串）
     */
    static String s(String v) {
        return v != null ? v : "";
    }

    /**
     * String 转 BigDecimal（空字符串返回null）
     */
    static BigDecimal toBd(String v) {
        return v.isEmpty() ? null : new BigDecimal(v);
    }

    /**
     * String 校验（空字符串返回null）
     */
    static String toStr(String v) {
        return v.isEmpty() ? null : v;
    }
}