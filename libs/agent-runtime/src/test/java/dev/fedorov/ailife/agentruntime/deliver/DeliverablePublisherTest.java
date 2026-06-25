package dev.fedorov.ailife.agentruntime.deliver;

import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.RenderedDoc;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliverablePublisherTest {

    private final DocRenderer renderer = mock(DocRenderer.class);
    private final MediaStoreClient media = mock(MediaStoreClient.class);

    @Test
    void publishRendersUploadsAndLinks() {
        DeliverablePublisher publisher = new DeliverablePublisher(renderer, media, "https://m.example.com/");
        UUID household = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID stored = UUID.randomUUID();
        Doc doc = Doc.builder("T").build();
        RenderedDoc rendered = new RenderedDoc(new byte[]{1, 2, 3}, "text/html", "board.html");
        when(renderer.render(doc)).thenReturn(rendered);
        when(media.upload(eq(household), eq(owner), eq("board.html"), eq("text/html"), any()))
                .thenReturn(Mono.just(dto(stored)));

        String link = publisher.publish(household, owner, doc).block();
        assertThat(link).isEqualTo("https://m.example.com/v1/media/" + stored);   // trailing slash trimmed
    }

    @Test
    void defaultThemeConstructorRendersHtmlAndLinks() {
        DeliverablePublisher publisher = new DeliverablePublisher(media, "https://m.example.com");
        UUID household = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID stored = UUID.randomUUID();
        when(media.upload(eq(household), eq(owner), eq("doc.html"), eq("text/html"), any()))
                .thenReturn(Mono.just(dto(stored)));

        String link = publisher.publish(household, owner,
                Doc.builder("T").section("S", List.of("p")).build()).block();
        assertThat(link).isEqualTo("https://m.example.com/v1/media/" + stored);
    }

    @Test
    void mediaUrlComposesFromBaseAndIsNullSafe() {
        DeliverablePublisher publisher = new DeliverablePublisher(renderer, media, "https://m.example.com");
        UUID id = UUID.randomUUID();
        assertThat(publisher.mediaUrl(id)).isEqualTo("https://m.example.com/v1/media/" + id);
        assertThat(publisher.mediaUrl((UUID) null)).isNull();
        assertThat(publisher.mediaUrl("  " + id + " ")).isEqualTo("https://m.example.com/v1/media/" + id);
        assertThat(publisher.mediaUrl("   ")).isNull();
        assertThat(publisher.mediaUrl((String) null)).isNull();
    }

    @Test
    void splitParagraphsKeepsNonBlankLines() {
        assertThat(DeliverablePublisher.splitParagraphs("a\n\n  b  \nc"))
                .containsExactly("a", "b", "c");
        assertThat(DeliverablePublisher.splitParagraphs(null)).isEmpty();
        assertThat(DeliverablePublisher.splitParagraphs("   ")).containsExactly("");
    }

    @Test
    void summaryTakesFirstLineWithFallbackAndCap() {
        assertThat(DeliverablePublisher.summary("First line\nsecond", "fb")).isEqualTo("First line");
        assertThat(DeliverablePublisher.summary("  ", "fallback")).isEqualTo("fallback");
        assertThat(DeliverablePublisher.summary(null, "fallback")).isEqualTo("fallback");
        String longLine = "x".repeat(400);
        String summarised = DeliverablePublisher.summary(longLine, "fb");
        assertThat(summarised).hasSize(278).endsWith("…");   // 277 chars + the ellipsis
    }

    private static MediaObjectDto dto(UUID id) {
        return new MediaObjectDto(id, UUID.randomUUID(), UUID.randomUUID(),
                "file", "text/html", 3, null, "test", Instant.now());
    }
}
