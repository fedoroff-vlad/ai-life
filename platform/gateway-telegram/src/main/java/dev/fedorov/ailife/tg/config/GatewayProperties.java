package dev.fedorov.ailife.tg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Telegram telegram = new Telegram();
    private Services services = new Services();
    /**
     * Bearer token required on {@code POST /internal/send} from notifier-service and
     * any other in-cluster caller. Empty in dev = endpoint refuses every request.
     */
    private String internalApiToken = "";

    public Telegram getTelegram() { return telegram; }
    public Services getServices() { return services; }
    public String getInternalApiToken() { return internalApiToken; }
    public void setInternalApiToken(String internalApiToken) {
        this.internalApiToken = internalApiToken;
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
    }
}
