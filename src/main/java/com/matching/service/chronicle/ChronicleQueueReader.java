package com.matching.service.chronicle;

import com.matching.service.EventLog;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ExcerptTailer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class ChronicleQueueReader {

    private final ChronicleQueueManager queueManager;
    private final boolean transactionEnabled;

    public ChronicleQueueReader(ChronicleQueueManager queueManager, boolean transactionEnabled) {
        this.queueManager = queueManager;
        this.transactionEnabled = transactionEnabled;
    }

    public List<EventLog.Event> readEvents(String symbolId, long afterSeq) {
        ExcerptTailer tailer = queueManager.getTailer();
        if (tailer == null) {
            return new ArrayList<>();
        }

        final List<EventLog.Event> events = new ArrayList<>();
        final Set<Long> committedSeqs = new HashSet<>();

        // 第一遍：收集所有已提交的序列号
        tailer.toStart();
        while (tailer.readDocument(wireIn -> {
            String type = wireIn.read("type").text();
            if ("commit".equals(type)) {
                long seq = wireIn.read("seq").int64();
                committedSeqs.add(seq);
            }
        })) {
            // 继续读取
        }

        // 第二遍：读取已提交的事件
        tailer.toStart();
        while (tailer.readDocument(wireIn -> {
            String type = wireIn.read("type").text();
            if ("event".equals(type)) {
                EventLog.Event event = wireIn.read("event").object(EventLog.Event.class);
                if (event.getSeq() > afterSeq &&
                    (committedSeqs.contains(event.getSeq()) || !transactionEnabled)) {
                    if (symbolId == null || symbolId.equals(event.getSymbolId())) {
                        events.add(event);
                    }
                }
            }
        })) {
            // 继续读取
        }

        return events;
    }

    public EventLog.Snapshot readSnapshot(String symbolId) {
        ExcerptTailer tailer = queueManager.getTailer();
        if (tailer == null) {
            return null;
        }

        final AtomicReference<EventLog.Snapshot> latestSnapshotRef = new AtomicReference<>();

        tailer.toStart();
        while (tailer.readDocument(wireIn -> {
            String type = wireIn.read("type").text();
            if ("snapshot".equals(type)) {
                EventLog.Snapshot snapshot = wireIn.read("snapshot").object(EventLog.Snapshot.class);
                if (symbolId.equals(snapshot.getSymbolId())) {
                    EventLog.Snapshot current = latestSnapshotRef.get();
                    if (current == null || snapshot.getSeq() > current.getSeq()) {
                        latestSnapshotRef.set(snapshot);
                    }
                }
            }
        })) {
            // 继续读取
        }

        return latestSnapshotRef.get();
    }

    public long getMaxSequenceFromQueue() {
        ExcerptTailer tailer = queueManager.getTailer();
        if (tailer == null) {
            return 0;
        }

        final AtomicLong maxSeq = new AtomicLong(0);

        tailer.toStart();
        while (tailer.readDocument(wireIn -> {
            String type = wireIn.read("type").text();
            if ("event".equals(type)) {
                try {
                    EventLog.Event event = wireIn.read("event").object(EventLog.Event.class);
                    maxSeq.set(Math.max(maxSeq.get(), event.getSeq()));
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }
        })) {
            // 继续读取
        }

        return maxSeq.get();
    }

    public long getLastWriteTimestamp() {
        ExcerptTailer tailer = queueManager.getQueue().createTailer();
        final AtomicLong lastTimestamp = new AtomicLong(0);

        tailer.toEnd();

        // 向前查找最近的检查点或事件
        for (int i = 0; i < 100 && tailer.readDocument(wireIn -> {
            String type = wireIn.read("type").text();
            if ("checkpoint".equals(type) || "event".equals(type)) {
                try {
                    long timestamp = wireIn.read("timestamp").int64();
                    lastTimestamp.set(Math.max(lastTimestamp.get(), timestamp));
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }
        }); i++) {
            // 继续查找
        }

        return lastTimestamp.get();
    }
}