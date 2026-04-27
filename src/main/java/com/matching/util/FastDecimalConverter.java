package com.matching.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 高性能数值转换工具类
 * 针对高频交易场景优化的BigDecimal与long之间的转换
 */
public class FastDecimalConverter {

    // 定义精度常量：10^8，用于8位小数的数值转换
    public static final long PRECISION = 100_000_000L;
    // 定义小数位数常量，保留8位小数
    public static final int SCALE = 8;

    // 缓存池：预缓存0-999的常用BigDecimal对象，避免重复创建以提升性能
    private static final BigDecimal[] CACHED_DECIMALS = new BigDecimal[1000];
    private static final long[] CACHED_LONGS = new long[1000];

    /**
     * 静态代码块：初始化缓存
     * 预计算并缓存0到999对应的高精度小数和长整型值
     */
    static {
        for (int i = 0; i < 1000; i++) {
            CACHED_DECIMALS[i] = BigDecimal.valueOf(i, SCALE);
            CACHED_LONGS[i] = i * PRECISION;
        }
    }

    /**
     * 优化的BigDecimal转long方法
     * 对常用小数值使用缓存，极大提升转换性能
     * @param value 待转换的BigDecimal数值
     * @return 转换后的long型数值
     */
    public static long toLong(BigDecimal value) {
        if (value == null) return 0;

        // 快速路径：检查是否为缓存的常用值（小数位为8位且数值范围在0-999内）
        if (value.scale() == SCALE && value.precision() <= 3) {
            int intValue = value.movePointRight(SCALE).intValueExact();
            if (intValue >= 0 && intValue < CACHED_LONGS.length) {
                return CACHED_LONGS[intValue];
            }
        }

        // 标准转换路径：包含舍入和移位操作
        try {
            return value.setScale(SCALE, RoundingMode.HALF_DOWN)
                    .movePointRight(SCALE)
                    .longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Value overflow: " + value.toPlainString(), e);
        }
    }

    /**
     * 优化的long转BigDecimal方法
     * 对常用值使用预缓存对象，避免重复创建对象
     * @param value 待转换的long型数值
     * @return 转换后的BigDecimal数值
     */
    public static BigDecimal toBigDecimal(long value) {
        // 快速路径：检查是否为缓存的常用值（能被PRECISION整除且在缓存范围内）
        if (value >= 0 && value < (long) CACHED_DECIMALS.length * PRECISION && value % PRECISION == 0) {
            int index = (int) (value / PRECISION);
            if (index < CACHED_DECIMALS.length) {
                return CACHED_DECIMALS[index];
            }
        }

        // 标准转换路径：直接创建
        return BigDecimal.valueOf(value, SCALE);
    }
}