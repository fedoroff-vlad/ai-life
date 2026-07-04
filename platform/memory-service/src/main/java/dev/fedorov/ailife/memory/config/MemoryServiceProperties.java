package dev.fedorov.ailife.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memory")
public class MemoryServiceProperties {

    /** Default top-k for recall when the request omits it. */
    private int defaultK = 5;

    /** Hard cap on top-k to bound query cost. */
    private int maxK = 50;

    /**
     * Embedding dimension. Must match both the {@code embedding vector(N)} column
     * width AND whatever llm-gateway's active embedding provider returns. The mock
     * provider emits 384-dim; bge-m3 (Stage 5) is 1024-dim — when we flip provider
     * we will also widen the column.
     */
    private int dim = 384;

    /**
     * Base URL of profile-service, used by relation capture to resolve a person's
     * name to a {@code core.people} UUID (memory-from-chat / MFC-c).
     */
    private String profileBaseUrl = "http://profile-service:8082";

    /**
     * Base URL of conversation-service. Ambient capture (AC-4) sets a route-lock here to await the
     * owner's approval of an important inferred fact. Best-effort — a failure just drops the approval.
     */
    private String conversationBaseUrl = "http://conversation-service:8089";

    /**
     * Base URL of notifier-service. Ambient capture (AC-4) pushes the "заметил: … — записать?" question
     * here. Best-effort — a failure just means the owner isn't asked (the inferred fact is dropped).
     */
    private String notifierBaseUrl = "http://notifier-service:8084";

    /** Ambient / intuitive capture (AC-2+): fill the note tier from ordinary chat. */
    private final AmbientCapture ambientCapture = new AmbientCapture();

    public int getDefaultK() { return defaultK; }
    public void setDefaultK(int defaultK) { this.defaultK = defaultK; }
    public int getMaxK() { return maxK; }
    public void setMaxK(int maxK) { this.maxK = maxK; }
    public int getDim() { return dim; }
    public void setDim(int dim) { this.dim = dim; }
    public String getProfileBaseUrl() { return profileBaseUrl; }
    public void setProfileBaseUrl(String profileBaseUrl) { this.profileBaseUrl = profileBaseUrl; }
    public String getConversationBaseUrl() { return conversationBaseUrl; }
    public void setConversationBaseUrl(String conversationBaseUrl) { this.conversationBaseUrl = conversationBaseUrl; }
    public String getNotifierBaseUrl() { return notifierBaseUrl; }
    public void setNotifierBaseUrl(String notifierBaseUrl) { this.notifierBaseUrl = notifierBaseUrl; }
    public AmbientCapture getAmbientCapture() { return ambientCapture; }

    /**
     * Ambient-capture toggle (plans/ambient-capture.md). When enabled, {@code CaptureService} also
     * promotes note-worthy chat into curated {@code memory.note}s. Off by default — this is opt-in
     * until the intake quality is trusted.
     */
    public static class AmbientCapture {
        private boolean enabled = false;

        /**
         * AC-3 dedup threshold: before writing an ambient note, its {@code source=note} neighbours are
         * recalled; if the nearest one is within this cosine distance the write is skipped as a
         * near-duplicate. Smaller = stricter (only near-identical text dedups).
         */
        private double dedupDistance = 0.15;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getDedupDistance() { return dedupDistance; }
        public void setDedupDistance(double dedupDistance) { this.dedupDistance = dedupDistance; }
    }
}
