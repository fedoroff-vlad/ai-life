package dev.fedorov.ailife.mcp.creator.web;

import dev.fedorov.ailife.contracts.creator.SaveContentPieceInput;
import dev.fedorov.ailife.mcp.creator.tools.CreatorMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Non-MCP REST passthrough for saving a generated content piece. The content-strategist flow (CR-d)
 * persists the synthesized plan as one {@code draft} {@link SaveContentPieceInput} over HTTP (the
 * MCP/SSE transport can't be MockWebServer'd) rather than through an LLM-driven tool call. Delegates
 * straight to {@link CreatorMcpTools#saveContentPiece} so the tool's invariants apply identically.
 * Mirrors {@code InternalTrendController}.
 */
@RestController
@RequestMapping("/internal/content-piece")
public class InternalContentPieceController {

    private final CreatorMcpTools tools;

    public InternalContentPieceController(CreatorMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveContentPieceInput input) {
        try {
            return ResponseEntity.ok(tools.saveContentPiece(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
