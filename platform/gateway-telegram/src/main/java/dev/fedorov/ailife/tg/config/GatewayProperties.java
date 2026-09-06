package dev.fedorov.ailife.tg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Telegram telegram = new Telegram();
    private Services services = new Services();
    private Stt stt = new Stt();
    /**
     * Bearer token required on {@code POST /internal/send} from notifier-service and
     * any other in-cluster caller. Empty in dev = endpoint refuses every request.
     */
    private String internalApiToken = "";
    /**
     * Owner-allowlist of Telegram user ids permitted to <b>create an account on first contact</b>
     * (issue #627 — close the LLM/infra cost-abuse vector). The bot auto-provisions a personal
     * household for a brand-new sender, so an ungated bot lets any stranger who finds it consume the
     * owner's LLM budget (data stays isolated per household, but the spend does not).
     *
     * <p><b>Empty (the default) = allow all</b> — the gate is off, preserving the pre-#627 behaviour
     * for dev/CI/local runs. In prod the owner sets this (their own id plus anyone they trust) and
     * only listed ids may onboard. Two paths bypass the gate on purpose: an <b>already-provisioned</b>
     * user (a member onboarded earlier) always passes, and a <b>{@code /start <token>} family
     * invite</b> provisions the invitee regardless — the invite token is the authorization
     * (ADR-0001). Env: {@code GATEWAY_ALLOWED_TELEGRAM_IDS} (CSV).
     */
    private Set<Long> allowedTelegramIds = new HashSet<>();

    public Telegram getTelegram() { return telegram; }
    public Services getServices() { return services; }
    public Stt getStt() { return stt; }
    public String getInternalApiToken() { return internalApiToken; }
    public void setInternalApiToken(String internalApiToken) {
        this.internalApiToken = internalApiToken;
    }

    public Set<Long> getAllowedTelegramIds() { return allowedTelegramIds; }
    public void setAllowedTelegramIds(Set<Long> allowedTelegramIds) {
        this.allowedTelegramIds = allowedTelegramIds;
    }

    /**
     * Whether a <em>new</em> Telegram id may be onboarded (a personal household created for it). An
     * empty allowlist means the gate is off (allow all, back-compat); otherwise only listed ids pass.
     * Existing users and invite redemptions are handled by the caller and do not consult this.
     */
    public boolean isOnboardingAllowed(long telegramUserId) {
        return allowedTelegramIds.isEmpty() || allowedTelegramIds.contains(telegramUserId);
    }

    /** Front-door speech-to-text reliability gate (#489 RU-3). */
    public static class Stt {
        /**
         * A captionless voice note whose transcript confidence (0..1) is <b>known and below</b> this
         * value is treated as unintelligible: the gateway asks the owner to repeat instead of routing a
         * garbled transcript. An empty transcript is always treated as unintelligible; a {@code null}
         * confidence (engine gave no signal) is "unknown", never low, so it still routes. Internal
         * tunable — override via {@code gateway.stt.min-confidence} / env {@code GATEWAY_STT_MINCONFIDENCE}.
         */
        private double minConfidence = 0.55;

        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    }

    public static class Telegram {
        private String botUsername = "ai_life_bot";
        private String botToken = "";
        private String defaultHouseholdName = "default household";

        public String getBotUsername() { return botUsername; }
        public void setBotUsername(String botUsername) { this.botUsername = botUsername; }
        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getDefaultHouseholdName() { return defaultHouseholdName; }
        public void setDefaultHouseholdName(String defaultHouseholdName) {
            this.defaultHouseholdName = defaultHouseholdName;
        }

        public boolean isConfigured() {
            return botToken != null && !botToken.isBlank();
        }
    }

    public static class Services {
        private String profileBaseUrl = "http://profile-service:8082";
        private String orchestratorBaseUrl = "http://orchestrator:8083";
        private String mediaBaseUrl = "http://media-service:8088";
        private String mediaProcessingBaseUrl = "http://mcp-media-processing:8097";

        public String getProfileBaseUrl() { return profileBaseUrl; }
        public void setProfileBaseUrl(String profileBaseUrl) {
            this.profileBaseUrl = profileBaseUrl;
        }
        public String getOrchestratorBaseUrl() { return orchestratorBaseUrl; }
        public void setOrchestratorBaseUrl(String orchestratorBaseUrl) {
            this.orchestratorBaseUrl = orchestratorBaseUrl;
        }
        public String getMediaBaseUrl() { return mediaBaseUrl; }
        public void setMediaBaseUrl(String mediaBaseUrl) {
            this.mediaBaseUrl = mediaBaseUrl;
        }
        public String getMediaProcessingBaseUrl() { return mediaProcessingBaseUrl; }
        public void setMediaProcessingBaseUrl(String mediaProcessingBaseUrl) {
            this.mediaProcessingBaseUrl = mediaProcessingBaseUrl;
        }
    }
}
