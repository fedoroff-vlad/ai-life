package dev.fedorov.ailife.memory.note;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.note.NoteDto;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link NoteDto} into its markdown interchange form (SB-7 export): a YAML
 * frontmatter header (the manifest fields, straight from the row's columns) followed by the
 * CommonMark body verbatim ({@code [[wiki-links]]} + {@code #tags} intact). The source of truth
 * stays the Postgres row; this is the round-trippable export form — {@code id} in the header is
 * the durable anchor a future re-import matches on, so the filename is free to be the human title.
 *
 * <p>Pure/stateless. YAML is emitted via snakeyaml so titles/values carrying colons, quotes,
 * unicode, or emoji are escaped correctly. The open {@code frontmatter} jsonb bag is merged after
 * the canonical keys (extras win nothing they already occupy).
 */
public final class NoteMarkdown {

    private NoteMarkdown() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Canonical frontmatter keys emitted from columns — a jsonb-bag key with these names is skipped. */
    private static final List<String> RESERVED = List.of(
            "id", "title", "type", "tags", "source", "owner", "person", "created", "updated");
    private static final int MAX_FILENAME = 100;

    /** The full {@code .md} text: {@code ---} frontmatter {@code ---} then the body. */
    public static String render(NoteDto note) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", note.id().toString());
        fm.put("title", note.title());
        if (present(note.type())) fm.put("type", note.type());
        if (note.tags() != null && !note.tags().isEmpty()) fm.put("tags", note.tags());
        if (present(note.source())) fm.put("source", note.source());
        if (note.ownerId() != null) fm.put("owner", note.ownerId().toString());
        if (note.personId() != null) fm.put("person", note.personId().toString());
        if (note.createdAt() != null) fm.put("created", note.createdAt().toString());
        if (note.updatedAt() != null) fm.put("updated", note.updatedAt().toString());
        mergeExtras(fm, note.frontmatter());

        StringBuilder sb = new StringBuilder("---\n").append(yaml().dump(fm));
        if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        sb.append("---\n");
        String body = note.bodyMd();
        if (present(body)) {
            sb.append(body);
            if (body.charAt(body.length() - 1) != '\n') sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * The filename stem (no {@code .md}): the human title, sanitised of filesystem-reserved chars so
     * an Obsidian {@code [[Title]]} link still resolves. Falls back to the stable id when the title
     * sanitises to blank. The caller disambiguates duplicate stems.
     */
    public static String fileNameBase(NoteDto note) {
        String slug = slug(note.title());
        return slug.isEmpty() ? note.id().toString() : slug;
    }

    private static void mergeExtras(Map<String, Object> fm, JsonNode extra) {
        if (extra == null || !extra.isObject()) {
            return;
        }
        extra.fields().forEachRemaining(e -> {
            if (!RESERVED.contains(e.getKey())) {
                fm.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class));
            }
        });
    }

    private static String slug(String title) {
        if (title == null) {
            return "";
        }
        String s = title.trim().replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        return s.length() > MAX_FILENAME ? s.substring(0, MAX_FILENAME).trim() : s;
    }

    private static Yaml yaml() {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setSplitLines(false);
        return new Yaml(o);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
