package dev.fedorov.ailife.mcp.finance.web;

import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.finance.UpsertCategoryInput;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for categories. The {@code GET} lists a household's categories (delegating
 * to {@code list_categories}); the {@code POST} creates/updates one (delegating to
 * {@code upsert_category}, with {@code parentId} for grouping). Used by finance-agent's
 * {@code category-manager} flow to read the existing categories and create/group them from chat
 * without an LLM-driven MCP tool call. Mirrors {@link InternalAccountController} /
 * {@link InternalTransactionController}.
 */
@RestController
@RequestMapping("/internal")
public class InternalCategoryController {

    private final FinanceMcpTools tools;

    public InternalCategoryController(FinanceMcpTools tools) {
        this.tools = tools;
    }

    @GetMapping("/categories")
    public ResponseEntity<List<FinCategoryDto>> list(@RequestParam UUID householdId) {
        return ResponseEntity.ok(tools.listCategories(householdId));
    }

    /**
     * Delegates straight to {@link FinanceMcpTools#upsertCategory} so the tool's invariants
     * (required fields, (household, name, kind) uniqueness) apply identically. Validation failures → 400.
     */
    @PostMapping("/category")
    public ResponseEntity<?> upsert(@RequestBody UpsertCategoryInput input) {
        try {
            return ResponseEntity.ok(tools.upsertCategory(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
