package dev.fedorov.ailife.mcp.creator.tools;

import dev.fedorov.ailife.contracts.creator.ContentPieceDto;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SaveContentPieceInput;
import dev.fedorov.ailife.contracts.creator.SaveTrendInput;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import dev.fedorov.ailife.contracts.creator.TrendDto;
import dev.fedorov.ailife.mcp.creator.domain.ContentPiece;
import dev.fedorov.ailife.mcp.creator.domain.ContentPieceRepository;
import dev.fedorov.ailife.mcp.creator.domain.CreatorProfile;
import dev.fedorov.ailife.mcp.creator.domain.CreatorProfileRepository;
import dev.fedorov.ailife.mcp.creator.domain.Trend;
import dev.fedorov.ailife.mcp.creator.domain.TrendRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creator domain opener (CR-a): source-of-truth CRUD over creator.* (per-person content tracks +
 * the trend cache + the idea/draft history). The trend → ideas → drafts gather/synthesis flow lives
 * in creator-agent; this MCP is intentionally low-level — it just persists what the agent gathers
 * and generates.
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that household (mirrors
 * mcp-nutrition / mcp-wardrobe). Per-person attribution is the optional ownerId.
 */
@Component
public class CreatorMcpTools {

    /** Default and hard caps for the list tools, so an unbounded read can't sweep the table. */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final CreatorProfileRepository profiles;
    private final TrendRepository trends;
    private final ContentPieceRepository pieces;

    public CreatorMcpTools(CreatorProfileRepository profiles, TrendRepository trends,
                           ContentPieceRepository pieces) {
        this.profiles = profiles;
        this.trends = trends;
        this.pieces = pieces;
    }

    // ---------- creator profile ----------

    @Tool(description = """
            Upsert a person's creator track. Keyed on (householdId, ownerId); a null ownerId is the
            household-default track. `householdId` is required. This is a full set: every supplied
            field overwrites the stored value. `platforms` (target platforms) and `guardrails`
            (no-clickbait / brand rules) are free-form JSON; niche/audience/tone/goals are the
            content direction.
            """)
    @Transactional
    public CreatorProfileDto setCreatorProfile(SetCreatorProfileInput input) {
        requireField(input.householdId(), "householdId");
        CreatorProfile profile = profiles.findForOwner(input.householdId(), input.ownerId())
                .orElseGet(() -> new CreatorProfile(
                        UUID.randomUUID(), input.householdId(), input.ownerId()));
        profile.setNiche(input.niche());
        profile.setAudience(input.audience());
        profile.setTone(input.tone());
        profile.setPlatforms(input.platforms());
        profile.setGoals(input.goals());
        profile.setGuardrails(input.guardrails());
        profile.setNotes(input.notes());
        return profiles.save(profile).toDto();
    }

    @Tool(description = """
            Get the creator track for a person, treating a null `ownerId` as the household-default
            track. Returns null if no track has been set yet.
            """)
    @Transactional(readOnly = true)
    public CreatorProfileDto getCreatorProfile(UUID householdId, UUID ownerId) {
        requireField(householdId, "householdId");
        return profiles.findForOwner(householdId, ownerId)
                .map(CreatorProfile::toDto)
                .orElse(null);
    }

    // ---------- trend cache ----------

    @Tool(description = """
            Save a gathered trend into the cache. Only `householdId` + `title` are required;
            `capturedAt` defaults to now. Set `ownerId` to attribute it to a person (null =
            household-shared). `source` is the origin (web|youtube|reddit|telegram|rss), `url` the
            source link, `metrics` the free-form per-source signal (score/engagement).
            """)
    @Transactional
    public TrendDto saveTrend(SaveTrendInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.title(), "title");
        Instant capturedAt = input.capturedAt() != null ? input.capturedAt() : Instant.now();
        Trend trend = new Trend(UUID.randomUUID(), input.householdId(), input.ownerId(),
                capturedAt, input.title());
        trend.setSource(input.source());
        trend.setPlatform(input.platform());
        trend.setUrl(input.url());
        trend.setSummary(input.summary());
        trend.setMetrics(input.metrics());
        return trends.save(trend).toDto();
    }

    @Tool(description = """
            List cached trends in a household, most recently captured first. Pass `ownerId` to scope
            to one person; omit it for the whole household. `limit` caps the count (default 20, max 200).
            """)
    @Transactional(readOnly = true)
    public List<TrendDto> listTrends(UUID householdId, UUID ownerId, Integer limit) {
        requireField(householdId, "householdId");
        Pageable page = PageRequest.of(0, clampLimit(limit));
        List<Trend> rows = ownerId == null
                ? trends.findByHouseholdIdOrderByCapturedAtDesc(householdId, page)
                : trends.findByHouseholdIdAndOwnerIdOrderByCapturedAtDesc(householdId, ownerId, page);
        return rows.stream().map(Trend::toDto).toList();
    }

    // ---------- content pieces (ideas / drafts) ----------

    @Tool(description = """
            Save a generated content piece. `householdId` + `kind` (idea|draft) are required; `status`
            defaults to `new`. Set `ownerId` to attribute it to a person. `body`/`cta`/`hashtags`
            carry a draft's full content (an idea may leave them empty); `trendId` is the optional
            provenance pointer to the source trend.
            """)
    @Transactional
    public ContentPieceDto saveContentPiece(SaveContentPieceInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.kind(), "kind");
        ContentPiece piece = new ContentPiece(UUID.randomUUID(), input.householdId(),
                input.ownerId(), input.kind());
        piece.setPlatform(input.platform());
        piece.setTitle(input.title());
        piece.setBody(input.body());
        piece.setCta(input.cta());
        piece.setHashtags(input.hashtags());
        piece.setStatus(input.status() != null && !input.status().isBlank() ? input.status() : "new");
        piece.setTrendId(input.trendId());
        return pieces.save(piece).toDto();
    }

    @Tool(description = """
            List generated content pieces in a household, most recently created first. Pass `kind`
            (idea|draft) to scope to one kind; omit it for both. `limit` caps the count (default 20,
            max 200).
            """)
    @Transactional(readOnly = true)
    public List<ContentPieceDto> listContentPieces(UUID householdId, String kind, Integer limit) {
        requireField(householdId, "householdId");
        Pageable page = PageRequest.of(0, clampLimit(limit));
        List<ContentPiece> rows = (kind == null || kind.isBlank())
                ? pieces.findByHouseholdIdOrderByCreatedAtDesc(householdId, page)
                : pieces.findByHouseholdIdAndKindOrderByCreatedAtDesc(householdId, kind, page);
        return rows.stream().map(ContentPiece::toDto).toList();
    }

    @Tool(description = """
            Get one generated content piece by id, or null if it doesn't exist.
            """)
    @Transactional(readOnly = true)
    public ContentPieceDto getContentPiece(UUID id) {
        requireField(id, "id");
        return pieces.findById(id).map(ContentPiece::toDto).orElse(null);
    }

    @Tool(description = """
            Delete a generated content piece and return the deleted row (so the agent can confirm /
            offer undo). Throws if the id is unknown. Confirming the destructive action is the agent
            layer's job.
            """)
    @Transactional
    public ContentPieceDto deleteContentPiece(UUID id) {
        requireField(id, "id");
        ContentPiece piece = pieces.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Content piece not found: " + id));
        ContentPieceDto dto = piece.toDto();
        pieces.delete(piece);
        return dto;
    }

    // ---------- helpers ----------

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
