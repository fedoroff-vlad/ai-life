package dev.fedorov.ailife.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Background poll drain for {@code bus.inbox}: every {@code poll-interval} it re-attempts due
 * {@code PENDING}/{@code FAILED} rows via the registered {@link InboxHandler}, with exponential
 * backoff + jitter, and retires a poison message to {@code DEAD} after {@code max-attempts}.
 *
 * <p>Unlike {@code PostgresEventBusListener} (LISTEN/NOTIFY, sub-second outbound latency) this side is
 * purely poll-based on purpose: a freshly recorded row is scheduled {@code next_attempt_at} a grace
 * window ahead, so the synchronous ingress attempt normally completes and marks it {@code PROCESSED}
 * first — the redriver only ever claims rows the in-request path failed to finish. Inbound redrive
 * after a multi-minute outage does not need sub-second latency, and skipping NOTIFY removes the
 * sync-vs-redrive double-dispatch race entirely.
 *
 * <p>Each row is claimed with {@code FOR UPDATE SKIP LOCKED} and the handler runs inside that
 * transaction, so concurrent redrivers never double-process one row and a success/failure verdict is
 * committed atomically with the claim. A handler failure reschedules the row (attempts++ / backoff)
 * rather than rolling back, so a poison row can reach its terminal {@code DEAD} state.
 */
public class PostgresInboxRedriver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresInboxRedriver.class);

    private final DataSource dataSource;
    private final InboxHandler handler;
    private final DeadLetterHandler deadLetter;
    private final long pollMillis;
    private final long backoffMillis;
    private final long maxBackoffMillis;
    private final int maxAttempts;

    private volatile boolean running;
    private Thread thread;

    public PostgresInboxRedriver(DataSource dataSource, InboxProperties props,
                                 InboxHandler handler, DeadLetterHandler deadLetter) {
        this.dataSource = dataSource;
        this.handler = handler;
        this.deadLetter = deadLetter == null ? DeadLetterHandler.noop() : deadLetter;
        this.pollMillis = props.getPollInterval().toMillis();
        this.backoffMillis = props.getBackoff().toMillis();
        this.maxBackoffMillis = props.getMaxBackoff().toMillis();
        this.maxAttempts = props.getMaxAttempts();
    }

    /** Start the background drain loop. Idempotent. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "inbox-redriver");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (running) {
            try {
                drainDue();
            } catch (RuntimeException e) {
                log.error("inbox redrive pass failed", e);
            }
            sleep(pollMillis);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** Drain all currently-due rows, oldest schedule first, until none remain (or shutdown). */
    private void drainDue() {
        while (running && drainOne()) {
            // keep going while a due row was claimed
        }
    }

    /** @return true if a due row was claimed and given a terminal verdict this pass, false if none were due. */
    private boolean drainOne() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                InboxMessage msg = claimNext(conn);
                if (msg == null) {
                    conn.rollback();
                    return false;
                }
                InboxMessage dead = attempt(conn, msg);
                conn.commit();
                if (dead != null) {
                    notifyDead(dead);
                }
                return true;
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                log.error("inbox redrive claim/commit failed; row left for next poll", e);
                return false;
            }
        } catch (SQLException e) {
            log.error("inbox redrive could not open a connection", e);
            return false;
        }
    }

    /**
     * Run the handler for a claimed row and record the verdict on {@code conn} (still in the claim's
     * transaction). @return the row if it just went terminal {@code DEAD} (so the caller fires the
     * dead-letter hook after commit), else {@code null}.
     */
    private InboxMessage attempt(Connection conn, InboxMessage msg) throws SQLException {
        try {
            handler.handle(msg);
            markProcessed(conn, msg.id());
            return null;
        } catch (Exception e) {
            int attempts = msg.attempts() + 1;
            String error = truncate(String.valueOf(e));
            if (attempts >= maxAttempts) {
                markDead(conn, msg.id(), attempts, error);
                log.error("inbox message {} DEAD after {} attempts", msg.id(), attempts, e);
                return msg;
            }
            long delay = backoffMillis(attempts);
            markFailed(conn, msg.id(), attempts, delay, error);
            log.warn("inbox redrive attempt {} for {} failed; retry in {}ms", attempts, msg.id(), delay, e);
            return null;
        }
    }

    private void notifyDead(InboxMessage msg) {
        try {
            deadLetter.onDead(msg);
        } catch (RuntimeException e) {
            log.warn("inbox dead-letter hook failed for {}", msg.id(), e);
        }
    }

    private InboxMessage claimNext(Connection conn) throws SQLException {
        String sql = """
                SELECT id, dedup_key, payload::text AS payload, attempts, created_at
                FROM bus.inbox
                WHERE status IN ('PENDING', 'FAILED')
                  AND next_attempt_at <= now()
                ORDER BY next_attempt_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new InboxMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("dedup_key"),
                    rs.getString("payload"),
                    rs.getInt("attempts"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }

    private void markProcessed(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE bus.inbox
                SET status = 'PROCESSED', processed_at = now(), attempts = attempts + 1
                WHERE id = ?
                """)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    private void markFailed(Connection conn, UUID id, int attempts, long delayMillis, String error)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE bus.inbox
                SET status = 'FAILED', attempts = ?, last_error = ?,
                    next_attempt_at = now() + make_interval(secs => ?)
                WHERE id = ?
                """)) {
            ps.setInt(1, attempts);
            ps.setString(2, error);
            ps.setDouble(3, delayMillis / 1000.0);
            ps.setObject(4, id);
            ps.executeUpdate();
        }
    }

    private void markDead(Connection conn, UUID id, int attempts, String error) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE bus.inbox
                SET status = 'DEAD', attempts = ?, last_error = ?, processed_at = now()
                WHERE id = ?
                """)) {
            ps.setInt(1, attempts);
            ps.setString(2, error);
            ps.setObject(3, id);
            ps.executeUpdate();
        }
    }

    /** Exponential backoff (base * 2^(attempts-1)) capped at max, plus up to 20% jitter. */
    private long backoffMillis(int attempts) {
        long exp = backoffMillis;
        for (int i = 1; i < attempts && exp < maxBackoffMillis; i++) {
            exp = Math.min(maxBackoffMillis, exp * 2);
        }
        long jitter = (long) (exp * 0.2 * ThreadLocalRandom.current().nextDouble());
        return exp + jitter;
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
