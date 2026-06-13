package dev.fedorov.ailife.mcp.tasks.web;

import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import dev.fedorov.ailife.mcp.tasks.review.ReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Non-MCP REST passthrough for the GTD weekly review — for system callers driven by
 * scheduler-service (no LLM tax, deterministic). {@code GET /internal/review?householdId=<uuid>}
 * returns the {@link WeeklyReviewResult} aggregate the tasks-agent's {@code weekly-review} skill
 * enriches its wake payload with. Mirrors mcp-finance's {@code InternalBudgetController}.
 */
@RestController
public class InternalReviewController {

    private final ReviewService review;

    public InternalReviewController(ReviewService review) {
        this.review = review;
    }

    @GetMapping("/internal/review")
    public WeeklyReviewResult review(@RequestParam UUID householdId) {
        return review.review(householdId);
    }
}
