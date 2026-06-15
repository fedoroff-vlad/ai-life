package dev.fedorov.ailife.bus;

import org.springframework.context.SmartLifecycle;

import javax.sql.DataSource;
import java.util.function.Consumer;

/**
 * Spring-lifecycle wrapper around a {@link PostgresEventBusListener}: starts the LISTEN
 * loop when the context starts and closes it on shutdown. A consumer service registers
 * this as a {@code @Bean}, passing its own handler:
 *
 * <pre>{@code
 * @Bean
 * EventBusListenerContainer notifyListener(DataSource ds, EventBusProperties props, NotifyHandler h) {
 *     return new EventBusListenerContainer(ds, props, h::onEvent);
 * }
 * }</pre>
 *
 * Honors {@link EventBusProperties#isEnabled()} — when disabled, {@code start()} is a no-op.
 */
public class EventBusListenerContainer implements SmartLifecycle {

    private final PostgresEventBusListener listener;
    private final boolean enabled;
    private volatile boolean running;

    public EventBusListenerContainer(DataSource dataSource, EventBusProperties props,
                                     Consumer<EventBusMessage> handler) {
        this.enabled = props.isEnabled();
        this.listener = new PostgresEventBusListener(
                dataSource, props.getChannel(), handler, props.getPollMillis());
    }

    @Override
    public void start() {
        if (running || !enabled) {
            return;
        }
        listener.start();
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        listener.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
