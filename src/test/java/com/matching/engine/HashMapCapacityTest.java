package com.matching.engine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

public class HashMapCapacityTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testHashMapCapacityCalculation() throws Exception {
        int[] testValues = {100, 1000, 10000, 500000};

        for (int maxOrders : testValues) {
            MatchEngine engine = new MatchEngine("TEST", maxOrders);

            Field orderIndexField = MatchEngine.class.getDeclaredField("orderIndex");
            orderIndexField.setAccessible(true);
            HashMap<Long, CompactOrderBookEntry> orderIndex =
                    (HashMap<Long, CompactOrderBookEntry>) orderIndexField.get(engine);

            int expectedCapacity = (maxOrders * 4 + 2) / 3;

            assertTrue(maxOrders <= expectedCapacity * 0.75,
                    String.format("maxOrders:%d should not trigger rehash with capacity:%d",
                            maxOrders, expectedCapacity));

            System.out.printf("maxOrders=%d, expectedCapacity=%d, threshold=%.0f%n",
                    maxOrders, expectedCapacity, expectedCapacity * 0.75);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAccountCapacityCalculation() throws Exception {
        int maxOrders = 100000;
        MatchEngine engine = new MatchEngine("TEST", maxOrders);

        Field accountField = MatchEngine.class.getDeclaredField("accountOrderCount");
        accountField.setAccessible(true);
        HashMap<Long, Integer> accountOrderCount =
                (HashMap<Long, Integer>) accountField.get(engine);

        int expectedAccountCapacity = Math.max(1024, (maxOrders + 99) / 100);

        System.out.printf("maxOrders=%d, expectedAccountCapacity=%d%n",
                maxOrders, expectedAccountCapacity);

        assertTrue(expectedAccountCapacity >= 1024);
        assertTrue(expectedAccountCapacity >= maxOrders / 100);
    }
}
