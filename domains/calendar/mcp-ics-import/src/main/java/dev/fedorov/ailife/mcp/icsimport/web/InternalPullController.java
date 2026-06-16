package dev.fedorov.ailife.mcp.icsimport.web;

import dev.fedorov.ailife.contracts.calendar.PullCalendarResult;
import dev.fedorov.ailife.mcp.icsimport.config.McpIcsImportProperties;
import dev.fedorov.ailife.mcp.icsimport.domain.IcsSubscription;
import dev.fedorov.ailife.mcp.icsimport.domain.IcsSubscriptionRepository;
import dev.fedorov.ailife.mcp.icsimport.sync.SubscriptionSync;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Non-MCP REST entry point for system callers (calendar-agent's ics.pull trigger
 * handler — driven by scheduler-service via orchestrator). Bypasses the MCP / LLM
 * surface entirely: scheduler ticks should not pay the LLM tax. The MCP tool
 * {@code pull_calendar} stays the right entry point for user-initiated pulls.
 */
@RestController
@RequestMapping("/internal/pull")
public class InternalPullController {

    private final IcsSubscriptionRepository subs;
    private final SubscriptionSync sync;
    private final McpIcsImportProperties props;

    public InternalPullController(IcsSubscriptionRepository subs,
                                  SubscriptionSync sync,
                                  McpIcsImportProperties props) {
        this.subs = subs;
        this.sync = sync;
        this.props = props;
    }

    @PostMapping("/{subscriptionId}")
    public ResponseEntity<PullCalendarResult> pull(@PathVariable UUID subscriptionId) {
        IcsSubscription sub = subs.findById(subscriptionId).orElse(null);
        if (sub == null) {
            return ResponseEntity.notFound().build();
        }
        PullCalendarResult result = sync.pull(sub, sourceCalendar(sub.getSlug()));
        return ResponseEntity.ok(result);
    }

    private String sourceCalendar(String slug) {
        return props.getCollectionPrefix() + "-" + slug;
    }
}
