package dev.fedorov.ailife.agentruntime.coordinate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic unit proof of the injection-guard mechanism (the model-side proof that it actually
 * resists a payload lives in {@code GoldenResearchInjectionResistanceTest}). Asserts the guard names
 * the untrusted sources and the data-not-instructions rule, and that {@link UntrustedContent#fence}
 * brackets a value both sides.
 */
class UntrustedContentTest {

    @Test
    void guardFramesRetrievedContentAsData() {
        String g = UntrustedContent.GUARD;
        assertThat(g).isNotBlank();
        // Names the concrete untrusted surfaces so the rule is not abstract.
        assertThat(g).containsIgnoringCase("web").containsIgnoringCase("OCR").containsIgnoringCase("transcript");
        // The load-bearing rule: treat as data, never as instructions.
        assertThat(g).containsIgnoringCase("data").containsIgnoringCase("never as instructions");
        assertThat(g).containsIgnoringCase("ignore");
        // Rules come from the system prompts + user, not the retrieved content.
        assertThat(g).containsIgnoringCase("user's own message");
    }

    @Test
    void fenceBracketsTheValueOnBothSides() {
        String f = UntrustedContent.fence("web-page", "hello <script> world");
        assertThat(f)
                .startsWith("<<UNTRUSTED web-page>>")
                .endsWith("<<END UNTRUSTED web-page>>")
                .contains("hello <script> world");
    }

    @Test
    void fenceToleratesNull() {
        assertThat(UntrustedContent.fence("ocr", null))
                .isEqualTo("<<UNTRUSTED ocr>>\n\n<<END UNTRUSTED ocr>>");
    }
}
