package dev.fedorov.ailife.bus;

import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * LISTEN/NOTIFY adapter: holds one dedicated connection LISTENing on the bus channel
 * and, on every wake (a NOTIFY or the poll timeout), drains PENDING {@code bus.outbox}
 * rows and hands each to the registered handler, then marks it PUBLISHED.
 *
 * <p>At-least-once: NOTIFY only wakes the loop early — the periodic poll guarantees
 * delivery even if a notification is missed (listener was down, NOTIFY lost). Each row
 * is claimed, dispatched, and marked PUBLISHED in one transaction, so a handler that
 * throws rolls back and leaves the row PENDING for the next poll. Rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED} so multiple listeners never double-process one row.
 *
 * <p>Single-handler by design for B1; a routing/multi-consumer API is Track B2. A
 * handler that always throws head-of-line-blocks its drain pass until fixed (no
 * dead-letter / retry cap yet — future work).
 */
public class PostgresEventBusListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresEventBusListener.class);

    private final DataSource dataSource;
    private final String channel;
    private final Consumer<EventBusMessage> handler;
    private final long pollMillis;

    private volatile boolean running;
    private Thread thread;
    private Connection listenConn;

    public PostgresEventBusListener(DataSource dataSource, String channel,
                                    Consumer<EventBusMessage> handler) {
        this(dataSource, channel, handler, 5000L);
    }

    public PostgresEventBusListener(DataSource dataSource, String channel,
                                    Consumer<EventBusMessage> handler, long pollMillis) {
        this.dataSource = dataSource;
        this.channel = channel;
        this.handler = handler;
        this.pollMillis = pollMillis;
    }

    /** Open the LISTEN connection and start the background drain loop. Idempotent. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "event-bus-listener-" + channel);
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            listenConn = dataSource.getConnection();
            try (Statement st = listenConn.createStatement()) {
                st.execute("LISTEN " + channel);
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);

            // Drain anything already PENDING before we blocked on the first NOTIFY.
            drainPending();

            while (running) {
                // Blocks up to pollMillis for a NOTIFY; returns early on one, null on timeout.
                pg.getNotifications((int) pollMillis);
                if (!running) {
                    break;
                }
                drainPending();
            }
        } catch (SQLException e) {
            if (running) {
                log.error("event-bus listener on channel '{}' stopped on error", channel, e);
            }
        } finally {
            closeQuietly();
        }
    }

    /**
     * Claim and dispatch PENDING rows oldest-first, one transaction per row, until the
     * queue is empty or a handler fails (the failing row stays PENDING for the next poll).
     */
    private void drainPending() {
        while (running && drainOne()) {
            // keep going while a row was successfully processed
        }
    }

    /** @return true if a row was claimed and published, false if the queue is empty or the handler failed. */
    private boolean drainOne() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                EventBusMessage msg = claimNext(conn);
                if (msg == null) {
                    conn.rollback();
                    return false;
                }
                handler.accept(msg);
                markPublished(conn, msg.id());
                conn.commit();
                return true;
            } catch (RuntimeException e) {
                conn.rollback();
                log.warn("event-bus handler failed (topic={}); leaving row PENDING for retry",
                        e.getMessage(), e);
                return false;
            }
        } catch (SQLException e) {
            log.error("event-bus drain failed on channel '{}'", channel, e);
            return false;
        }
    }

    private EventBusMessage claimNext(Connection conn) throws SQLException {
        String sql = """
                SELECT id, topic, household_id, payload::text AS payload, created_at
                FROM bus.outbox
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new EventBusMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("topic"),
                    rs.getObject("household_id", UUID.class),
                    rs.getString("payload"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }

    private void markPublished(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE bus.outbox SET status = 'PUBLISHED', published_at = now() WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        closeQuietly();
    }

    private void closeQuietly() {
        Connection c = listenConn;
        listenConn = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // best-effort
            }
        }
    }
}
