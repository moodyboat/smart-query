package com.smartquery.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 环形错误缓冲 — 直译 Claude Code log.ts 的 Ring Buffer
 */
public class RingBufferLog {

    private final int capacity;
    private final List<String> buffer;
    private int head = 0;
    private int size = 0;

    public RingBufferLog(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayList<>(Collections.nCopies(capacity, (String) null));
    }

    public synchronized void add(String entry) {
        buffer.set(head, entry);
        head = (head + 1) % capacity;
        if (size < capacity) size++;
    }

    public synchronized List<String> dump() {
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int idx = (head - size + i + capacity) % capacity;
            result.add(buffer.get(idx));
        }
        return result;
    }
}
