package com.matching.service.chronicle;

import com.matching.service.EventLog;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.wire.WriteMarshallable;

import java.util.concurrent.atomic.AtomicLong;

public class ChronicleQueueWriter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChronicleQueueWriter.class);
    private final ChronicleQueueManager queueManager;
    private final boolean transactionEnabled;
    private final AtomicLong committedSeq;

    public ChronicleQueueWriter(ChronicleQueueManager queueManager, boolean transactionEnabled, AtomicLong committedSeq) {
        this.queueManager = queueManager;
        this.transactionEnabled = transactionEnabled;
        this.committedSeq = committedSeq;
    }

    public void writeEvent(EventLog.Event event) {
        ExcerptAppender appender = queueManager.getAppender();
        if (appender == null) {
            throw new IllegalStateException("No appender available");
        }

        if (transactionEnabled) {
            writeTransactional(appender, event);
        } else {
            writeDirect(appender, event);
        }
    }

    public void writeEventSync(EventLog.Event event) {
        // 同步写入，强制刷盘
        writeEvent(event);
        // 在Chronicle Queue中，writeDocument已经是同步的
        // 如果需要额外的同步保证，可以在这里添加
    }

    private void writeTransactional(ExcerptAppender appender, EventLog.Event event) {
        appender.writeDocument(new EventWriter(event, false));
        appender.writeDocument(new CommitWriter(event.getSeq()));
        committedSeq.set(event.getSeq());
        log.debug("Transactional write completed, seq: {}", event.getSeq());
    }

    private void writeDirect(ExcerptAppender appender, EventLog.Event event) {
        appender.writeDocument(new EventWriter(event, true));
        committedSeq.set(event.getSeq());
        log.debug("Direct write completed, seq: {}", event.getSeq());
    }

    public void writeSnapshot(EventLog.Snapshot snapshot) {
        ExcerptAppender appender = queueManager.getAppender();
        if (appender == null) {
            return;
        }

        try {
            appender.writeDocument(new SnapshotWriter(snapshot));
            log.info("Snapshot written for symbol: {}, seq: {}, orders: {}",
                    snapshot.getSymbolId(), snapshot.getSeq(), snapshot.getAllOrders().size());
        } catch (Exception e) {
            log.error("Failed to write snapshot for symbol: {}", snapshot.getSymbolId(), e);
        }
    }

    public void writeCheckpoint(long seq, String instanceId) {
        ExcerptAppender appender = queueManager.getAppender();
        if (appender == null) {
            return;
        }

        try {
            appender.writeDocument(wireOut -> {
                wireOut.write("type").text("checkpoint");
                wireOut.write("seq").int64(seq);
                wireOut.write("timestamp").int64(System.currentTimeMillis());
                wireOut.write("instanceId").text(instanceId);
            });
            log.debug("Checkpoint written at seq: {}", seq);
        } catch (Exception e) {
            log.warn("Failed to write checkpoint", e);
        }
    }

    // Writer classes
    private static class EventWriter implements WriteMarshallable {
        private final EventLog.Event event;
        private final boolean committed;

        public EventWriter(EventLog.Event event, boolean committed) {
            this.event = event;
            this.committed = committed;
        }

        @Override
        public void writeMarshallable(net.openhft.chronicle.wire.WireOut wire) {
            wire.write("type").text("event");
            wire.write("committed").bool(committed);
            wire.write("event").object(event);
        }
    }

    private static class CommitWriter implements WriteMarshallable {
        private final long seq;

        public CommitWriter(long seq) {
            this.seq = seq;
        }

        @Override
        public void writeMarshallable(net.openhft.chronicle.wire.WireOut wire) {
            wire.write("type").text("commit");
            wire.write("seq").int64(seq);
        }
    }

    private static class SnapshotWriter implements WriteMarshallable {
        private final EventLog.Snapshot snapshot;

        public SnapshotWriter(EventLog.Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void writeMarshallable(net.openhft.chronicle.wire.WireOut wire) {
            wire.write("type").text("snapshot");
            wire.write("snapshot").object(snapshot);
        }
    }
}