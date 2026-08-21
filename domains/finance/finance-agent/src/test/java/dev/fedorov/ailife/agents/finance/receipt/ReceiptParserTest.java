package dev.fedorov.ailife.agents.finance.receipt;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.AccountClient;
import dev.fedorov.ailife.agents.finance.http.BasketCapturedClient;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit test for the receipt confirm-write undo handle (road-test #486, Track H — finance producer). */
class ReceiptParserTest {

    private final TransactionClient transactions = mock(TransactionClient.class);
    private final AgentManifest manifest = mock(AgentManifest.class);
    private final ObjectMapper json = new ObjectMapper();

    private final ReceiptParser parser = new ReceiptParser(
            mock(CaptionClient.class), mock(AccountClient.class), transactions,
            mock(BasketCapturedClient.class), mock(SkillRegistry.class), manifest, json,
            mock(MemoryClient.class));

    ReceiptParserTest() {
        when(manifest.name()).thenReturn("finance");
    }

    @Test
    void confirmedWriteAttachesAnUndoHandleForTheTransaction() {
        UUID txId = UUID.randomUUID();
        var input = new AddTransactionInput(UUID.randomUUID(), UUID.randomUUID(), null, null,
                new BigDecimal("-1000.00"), "RUB", Instant.now(), "такси", "telegram", null);
        var saved = new FinTransactionDto(txId, input.householdId(), input.accountId(), null, null,
                input.amount(), input.currency(), input.ts(), input.note(), "telegram", null, Instant.now());
        when(transactions.add(any())).thenReturn(Mono.just(saved));

        ObjectNode pending = json.createObjectNode();
        pending.set("input", json.valueToTree(input));
        pending.put("accountName", "Карта");
        var msg = new NormalizedMessage(input.householdId(), UUID.randomUUID(), MessageScope.PRIVATE,
                "да", List.of(), "telegram", "1", Instant.now());

        StepVerifier.create(parser.resume(new ResumeRequest(msg, pending)))
                .assertNext(resp -> {
                    assertThat(resp.agent()).isEqualTo("finance");
                    assertThat(resp.undo()).isNotNull();
                    assertThat(resp.undo().description()).contains("такси");
                    assertThat(resp.undo().action().path("transactionId").asString())
                            .isEqualTo(txId.toString());
                })
                .verifyComplete();
    }

    @Test
    void declinedConfirmWritesNothingAndHasNoUndo() {
        ObjectNode pending = json.createObjectNode();
        pending.set("input", json.createObjectNode());
        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "нет", List.of(), "telegram", "1", Instant.now());

        StepVerifier.create(parser.resume(new ResumeRequest(msg, pending)))
                .assertNext(resp -> assertThat(resp.undo()).isNull())
                .verifyComplete();
    }
}
