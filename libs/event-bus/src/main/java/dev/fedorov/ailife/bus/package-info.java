/**
 * Postgres LISTEN/NOTIFY-backed async event bus (Stage 4, Track B). A transactional
 * outbox ({@code bus.outbox}) is the durable log; {@link dev.fedorov.ailife.bus.OutboxPublisher}
 * writes a row + fires a NOTIFY, {@link dev.fedorov.ailife.bus.PostgresEventBusListener}
 * LISTENs and drains PENDING rows at-least-once.
 */
package dev.fedorov.ailife.bus;
