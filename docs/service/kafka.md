# Kafka (service)

Status: living document. This file lives under `docs/service/`. It describes the concrete Kafka configuration — topic, consumer group, serialization, partitioning. For what the event means and who produces/consumes it, see `docs/service/events.md`. For why this design was chosen over alternatives, see `docs/design-trade-offs.md § Event/messaging design`.

## 1. Topic

| Topic | Event | Key | Partitions (local) |
|---|---|---|---|
| `validation.processing-requested` | `ProcessingRequested` | `validationRequestId` (string form of the UUID) | 1 (local/dev via `docker/docker-compose.yml`; a real deployment would size this based on consumer parallelism) |

Keying by `validationRequestId` guarantees that, if this topic ever grows partitions, all messages for the same validation request land on the same partition and are processed in order relative to each other — relevant if this event catalog grows beyond a single event type per request.

## 2. Consumer group

Single consumer group: `validation-service`. All instances of the service share this group, so in a multi-instance deployment each `ProcessingRequested` message is handled by exactly one instance (competing consumers), not broadcast to all.

## 3. Serialization

Plain JSON via Spring Kafka's `JsonSerializer` (producer) / `JsonDeserializer` (consumer) — configured in `config/KafkaConfig.java`. No Avro, no Schema Registry: the payload is a single flat field (`validationRequestId`), and adding schema-registry infrastructure for that would be disproportionate for this slice (see `docs/design-trade-offs.md`). If the event payload grows in a future iteration, moving to Avro + Schema Registry is a config/dependency change, not a redesign, since serialization is isolated to the adapter layer (`adapter/out/messaging`) and never leaks into `domain`/`application`.

```yaml
# application.yml (excerpt)
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: validation-service
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "<base-package>.adapter.out.messaging"
    producer:
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

## 4. Delivery semantics

At-least-once delivery (Kafka's default with manual/auto-commit after processing). Duplicate deliveries are expected and handled at the domain level, not suppressed at the Kafka layer — see `docs/service/events.md` § 4.

## 5. Local setup

Kafka runs as a service in `docker/docker-compose.yml`, alongside `postgres` and `minio` (see `docs/service/architecture.md` § 3). It runs in KRaft mode, so there is no ZooKeeper container. No manual topic creation step is required for local development — `auto.create.topics.enable` is relied upon for `validation.processing-requested` in this slice; a production deployment would provision topics explicitly (out of scope here, see `docs/service/architecture.md` § 6 — Explicit out-of-scope).
