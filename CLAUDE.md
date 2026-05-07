# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

- Build: `mvn clean install -DskipTests`
- Run all tests: `mvn clean test`
- Run single test class: `mvn test -Dtest=<ClassName>` (e.g., `-Dtest=MatchEngineTest`)
- Run single test method: `mvn test -Dtest=<ClassName>#<methodName>`
- Run integration/performance tests: `mvn test -Dtest=<ClassName> -DskipIntegrationTests=false`
- Skip protobuf compilation: `mvn compile -Dprotoc.skip=true`
- Local run: `mvn spring-boot:run -Dspring-boot.run.profiles=local`

## Project Architecture

A **high-performance matching engine** using JDK 21 + Spring Boot 3.2.5 + Chronicle Queue + Kafka.

### Event Processing Pipeline

```
Kafka Consumer → Disruptor RingBuffer → MatchEventHandler → MatchEngine → ResultOutbox → Kafka Producer
                                            ↓
                                     EventLog (Chronicle Queue)
                                            ↓
                                    SnapshotService (Redis)
```

### Key Modules

- **`com.matching.disruptor`** — LMAX Disruptor-based event pipeline. `DisruptorManager` owns the ring buffer; `MatchEvent` is the reusable event object (cleared via `reset()`). `MatchEventHandler` processes events sequentially per symbol. `ResultSplitter` fans out match results to downstream services.

- **`com.matching.engine`** — Core matching logic. `MatchEngine` manages buy/sell `PriceLevelBook`s (ConcurrentSkipListMap-based), order index (HashMap), and object pool (`EntryPool`). `PriceLevelBook` uses descending ordering for buys (high price first) and ascending for sells (low price first). `CompactOrderBookEntry` and `OrderList` are lightweight memory-optimized structures.

- **`com.matching.service`** — Infrastructure layer:
  - `EventLog` (abstract) / `UnifiedChronicleQueueEventLog` — Chronicle Queue backed event persistence with global sequence numbers
  - `SnapshotService` — Periodic Redis snapshots (Protobuf-serialized order book state) for HA recovery
  - `OrderBookService` / `OrderBookManager` — In-memory order book state management and query
  - `ResultOutboxService` — Reliable outbox pattern for Kafka result publishing with retry queue
  - `AsyncDepthPublisher` — Non-blocking depth data publishing with backpressure handling
  - `IdempotentService` — Order deduplication (local cache + Redis TTL)
  - `RateLimiter` — Per-instance order rate limiting

- **`com.matching.ha`** — High availability (active-standby model):
  - `InstanceLeaderElection` — State machine: STANDBY → ACTIVE → DRAINING → STANDBY. Manual operator-controlled failover by default (`matching.ha.auto-failover=false`)
  - `AutoFailoverService` — Optional health-based automatic failover
  - `UnifiedHealthMonitorService` / `HaMonitor` — Instance health checks
  - `HAController` — Operator APIs for manual failover

- **`com.matching.mq`** — Kafka messaging layer:
  - `ReceiverMq` — Consumes trade commands from Kafka
  - `EventLogReplicationConsumer` — Consumes EventLog replication data on standby
  - `EventLogReplicationSender` / `EventLogReplicationService` — Replicates EventLog from active to standby via Kafka

- **`com.matching.command`** — Command pattern implementation: `PlaceOrderCommand`, `CancelOrderCommand`, `MatchExecutor`, `MatchContext`, `MatchResultBuilder`, `TakerOrderFactory`

- **`com.matching.config`** — Spring configuration classes for Kafka, Redis, Apollo, Chronicle Queue, and graceful shutdown

- **`com.matching.controller`** — REST API controllers: `TradeController` (order query with standby proxy), `OpsController`, `HAController`, `SystemOpsController`, `KafkaOpsController`, `OutboxMonitorController`

- **`com.matching.service.depth`** — Depth data pipeline: `BatchProcessor` → `DepthDataBuilder` → `DepthSender` with `BackpressureHandler`

- **`com.matching.service.outbox`** — Outbox reliability: `RetryQueueManager`, `FailureLogPersister`, `AlertService`, `DataSanitizer`

- **`com.matching.service.orderbook`** — Order book recovery: `EventLogLoader`, `OrderBookManager`, `LoadFailureHandler`, `OrderOperations`

- **`com.matching.util`** — Protobuf converters (`ProtoConverter`, `DepthProtoConverter`, `EventLogProtoConverter`, etc.), `SnowflakeIdGenerator`, `JsonUtil`, `ThreadFactoryManager`

### Data Flow

1. Orders arrive via Kafka (`ReceiverMq`) → placed on Disruptor ring buffer
2. `MatchEventHandler` processes orders: validates → executes matching → records events
3. Match results written to `EventLog` (Chronicle Queue on NVMe SSD) and published to Kafka
4. Periodic `SnapshotService` writes full order book state to Redis (Protobuf serialized)
5. On standby instance, `EventLogReplicationConsumer` receives and replays EventLog data
6. On failover: new active reads Redis snapshot → replays local EventLog delta → resumes (10-30ms)

### Configuration

- `src/resources/application.properties` — Base config with defaults
- `src/resources/application-{local,prod,production,demo,safety}.properties` — Per-environment overrides
- Apollo config center (production): `apollo.bootstrap.enabled=true`
- Local dev: `apollo.bootstrap.enabled=false` with direct property config

### Key Dependencies

- **LMAX Disruptor 4.0** — Ring buffer for high-throughput event processing
- **Chronicle Queue 2026.2** — Low-latency本地event persistence on NVMe SSD
- **Spring Boot 3.2.5** — Application framework, Kafka auto-config, Actuator
- **Apollo 2.5.0** — Distributed configuration management
- **Protobuf 4.29.3** — Serialization for EventLog, snapshots, depth data
- **Eclipse Collections 11.1** — High-performance collections
- **Redis (Jedis)** — Snapshot storage, idempotency cache, leader election
- **Micrometer + Prometheus** — Metrics and monitoring

### Test Organization

- Unit tests in `src/test/java/com/matching/`: mirror the main package structure
- Performance/benchmark tests in `com.matching.performance`: `EndToEndBenchmarkTest`, `StressTestSuite`, `PriceLevelBookBenchmarkTest`
- HA tests: `HAStateMachineTest`, `HARollingUpgradeTest`, `HAAutoRecoverTest`
- Engine tests: `MatchEngineTest`, `PriceLevelBookTest`
- Protobuf performance: `EventLogProtobufPerformanceTest`, `DataTypePerformanceTest`

### Important Notes

- **Maven must use Java 21** (`java.version=21` in pom.xml)
- **Protobuf compilation** uses `protobuf-maven-plugin` — protoc binary auto-detected via OS classifier
- **JVM flags required** for Chronicle Queue (configured in surefire argLine): `--add-opens java.base/java.lang.reflect=ALL-UNNAMED` etc.
- **HA is two-instance active-standby**, manual failover by default. Set `matching.ha.auto-failover=true` to enable automatic health-based failover.
- **Local dev**: use `application-local.properties` which disables Apollo and sets `matching.kafka.consumer.auto-startup=false`
- **Chronicle Queue data dir** defaults to `/data/matching/eventlog-queue` (prod) or `/tmp/matching/eventlog-queue` (local)
- **Snapshot interval**: 60s by default, tuned via `matching.eventlog.snapshot-interval-ms`
- **Ring buffer size**: 8192 (prod), 1024 (local). Must be power of 2.
