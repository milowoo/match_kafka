package com.matching.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Doubly-linked list of orders at the same price level.
 * - Normal orders: append to tail (FIFO / time priority)
 * - VIP orders: insert after last VIP (price priority > VIP priority > time priority)
 * - Remove: O(1) via prev/next pointers
 */
public class OrderList implements Iterable<CompactOrderBookEntry> {

    CompactOrderBookEntry head;
    CompactOrderBookEntry tail;
    private int size;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    /**
     * Add order with priority.
     * vip=false: append to tail (time priority)
     * vip=true: insert after the last VIP order (before first non-VIP)
     */
    public void add(CompactOrderBookEntry entry, boolean vip) {
        entry.prev = null;
        entry.next = null;

        if (size == 0) {
            head = tail = entry;
            size++;
            return;
        }

        if (!vip) {
            // Append to tail
            entry.prev = tail;
            tail.next = entry;
            tail = entry;
        } else {
            // Insert after last VIP (scan from head, VIPs are at the front)
            CompactOrderBookEntry insertAfter = null;
            CompactOrderBookEntry cur = head;
            while (cur != null && cur.vip) {
                insertAfter = cur;
                cur = cur.next;
            }

            if (insertAfter == null) {
                // No VIP yet, insert at head
                entry.next = head;
                head.prev = entry;
                head = entry;
            } else {
                // Insert after insertAfter
                entry.next = insertAfter.next;
                entry.prev = insertAfter;
                if (insertAfter.next != null) {
                    insertAfter.next.prev = entry;
                } else {
                    tail = entry;
                }
                insertAfter.next = entry;
            }
        }
        size++;
    }

    /**
     * Add order to tail (default: non-VIP, time priority).
     */
    public void add(CompactOrderBookEntry entry) {
        add(entry, entry.vip);
    }

    /**
     * Remove a specific order by reference. O(1).
     */
    public void remove(CompactOrderBookEntry entry) {
        if (entry.prev != null) {
            entry.prev.next = entry.next;
        } else {
            head = entry.next;
        }

        if (entry.next != null) {
            entry.next.prev = entry.prev;
        } else {
            tail = entry.prev;
        }

        entry.prev = null;
        entry.next = null;
        size--;
    }

    /**
     * Remove order by orderId. O(n) scan.
     */
    public boolean removeById(long orderId) {
        CompactOrderBookEntry cur = head;
        while (cur != null) {
            if (cur.orderId == orderId) {
                remove(cur);
                return true;
            }
            cur = cur.next;
        }
        return false;
    }

    /**
     * Collect all orders into a List (for serialization/snapshot).
     */
    public List<CompactOrderBookEntry> toList() {
        List<CompactOrderBookEntry> list = new ArrayList<>(size);
        CompactOrderBookEntry cur = head;
        while (cur != null) {
            list.add(cur);
            cur = cur.next;
        }
        return list;
    }

    @Override
    public Iterator<CompactOrderBookEntry> iterator() {
        return new Iterator<>() {
            CompactOrderBookEntry current = head;
            CompactOrderBookEntry lastReturned = null;

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public CompactOrderBookEntry next() {
                if (current == null) throw new NoSuchElementException();
                lastReturned = current;
                current = current.next;
                return lastReturned;
            }

            @Override
            public void remove() {
                if (lastReturned == null) throw new IllegalStateException();
                OrderList.this.remove(lastReturned);
                lastReturned = null;
            }
        };
    }
}