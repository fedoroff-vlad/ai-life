/**
 * Durable inbound <b>inbox</b>: persist-before-process + poll-based deferred redrive, so a user message
 * is never silently dropped when a downstream service is down or the ingress restarts mid-request
 * (issue #633).
 *
 * <p>Mirror of {@code dev.fedorov.ailife.bus} (the transactional outbox), opposite direction:
 * <ul>
 *   <li>{@link dev.fedorov.ailife.inbox.InboxWriter} — persist a normalized inbound message as
 *       {@code PENDING} before dispatch, deduped on the ingress key ({@code update_id}); mark
 *       {@code PROCESSED} once the in-request attempt delivered a reply.</li>
 *   <li>{@link dev.fedorov.ailife.inbox.PostgresInboxRedriver} — a background poll drain that re-attempts
 *       rows the in-request path failed to complete, with backoff, retiring a poison message to
 *       {@code DEAD} after {@code max-attempts}.</li>
 *   <li>{@link dev.fedorov.ailife.inbox.InboxRedriverContainer} — Spring-lifecycle wrapper; the redrive
 *       and dead-letter handlers are service-specific and supplied by the ingress.</li>
 * </ul>
 *
 * At-least-once, single-owner scale: one Postgres, one redriver instance expected; concurrent redrivers
 * are still safe ({@code FOR UPDATE SKIP LOCKED}).
 */
package dev.fedorov.ailife.inbox;
