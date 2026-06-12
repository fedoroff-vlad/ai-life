package dev.fedorov.ailife.agents.finance.web;

import dev.fedorov.ailife.agents.finance.intent.IntentRouter;
import dev.fedorov.ailife.agents.finance.moneypro.MoneyProCsvImporter;
import dev.fedorov.ailife.agents.finance.receipt.ReceiptParser;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Hit by orchestrator when intent routing selects {@code finance}. As of PR35
 * the controller delegates the heavy lifting to {@link IntentRouter}:
 * <ul>
 *   <li>If MCP tools are wired, the router prompts the LLM to either invoke
 *   a tool or reply directly; tool dispatches go through
 *   {@link dev.fedorov.ailife.agents.finance.tools.ToolDispatcher}.</li>
 *   <li>Otherwise the router falls back to a plain LLM chat (the pre-PR35
 *   behaviour preserved verbatim).</li>
 * </ul>
 * The controller stays thin — it only translates {@link NormalizedMessage}
 * to text and wraps the router's result back into an {@link IntentResponse}.
 *
 * <p>The {@code llmModel} field of the response carries the model id from
 * the routing turn — preserves the pre-PR35 contract the orchestrator's
 * intent tests assert on.
 */
@RestController
@RequestMapping("/agents/finance")
public class IntentController {

    private final IntentRouter router;
    private final ReceiptParser receiptParser;
    private final MoneyProCsvImporter csvImporter;
    private final AgentManifest manifest;

    public IntentController(IntentRouter router,
                            ReceiptParser receiptParser,
                            MoneyProCsvImporter csvImporter,
                            AgentManifest manifest) {
        this.router = router;
        this.receiptParser = receiptParser;
        this.csvImporter = csvImporter;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        // A photo attachment → receipt-parser; a document (file) → Money Pro CSV import;
        // otherwise normal text routing (LLM tool-call or chat).
        Optional<Attachment> image = attachment(message, "image");
        if (image.isPresent()) {
            return receiptParser.parse(message, image.get().storageUri());
        }
        Optional<Attachment> file = attachment(message, "file");
        if (file.isPresent()) {
            return csvImporter.importCsv(message, file.get().storageUri());
        }
        return router.route(message.text())
                .map(r -> new IntentResponse(manifest.name(), r.text(), r.llmModel()));
    }

    private static Optional<Attachment> attachment(NormalizedMessage message, String kind) {
        return message.attachments().stream()
                .filter(a -> kind.equals(a.kind()) && a.storageUri() != null)
                .findFirst();
    }
}
