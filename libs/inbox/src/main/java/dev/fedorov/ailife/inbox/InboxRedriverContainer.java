package dev.fedorov.ailife.inbox;

import org.springframework.context.SmartLifecycle;

import javax.sql.DataSource;

/**
 * Spring-lifecycle wrapper around a {@link PostgresInboxRedriver}: starts the poll drain when the
 * context starts and stops it on shutdown. A consumer (an ingress that persists to the inbox) registers
 * this as a {@code @Bean}, passing its own redrive + dead-letter handlers:
 *
 * <pre>{@code
 * @Bean
 * InboxRedriverContainer inboxRedriver(DataSource ds, InboxProperties props, GatewayInboxHandler h) {
 *     return new InboxRedriverContainer(ds, props, h, h::onDead);
 * }
 * }</pre>
 *
 * Honors {@link InboxProperties#isEnabled()} — when disabled, {@code start()} is a no-op.
 */
public class InboxRedriverContainer implements SmartLifecycle {

    private final PostgresInboxRedriver redriver;
    private final boolean enabled;
    private volatile boolean running;

    public InboxRedriverContainer(DataSource dataSource, InboxProperties props,
                                  InboxHandler handler, DeadLetterHandler deadLetter) {
        this.enabled = props.isEnabled();
        this.redriver = new PostgresInboxRedriver(dataSource, props, handler, deadLetter);
    }

    @Override
    public void start() {
        if (running || !enabled) {
            return;
        }
        redriver.start();
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        redriver.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
