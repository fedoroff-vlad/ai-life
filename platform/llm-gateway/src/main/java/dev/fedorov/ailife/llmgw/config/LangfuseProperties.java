package dev.fedorov.ailife.llmgw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse LLM-trace export (see architecture.md §LLM strategy: "tracing via Langfuse").
 * <p>
 * Off by default — {@code mock} dev runs and every other module's tests never emit. Enable in
 * staging/prod with {@code LANGFUSE_ENABLED=true} plus a public/secret key pair. Tracing is a
 * best-effort side channel: a Langfuse outage must never break or slow an LLM call, so the
 * tracer fires fire-and-forget and swallows its own errors.
 */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** Master switch. When false the tracer is a no-op and makes no HTTP calls. */
    private boolean enabled = false;

    /** Langfuse base URL — cloud ({@code https://cloud.langfuse.com}) or a self-hosted instance. */
    private String baseUrl = "https://cloud.langfuse.com";

    /** Project public key (HTTP Basic username on the ingestion endpoint). */
    private String publicKey;

    /** Project secret key (HTTP Basic password on the ingestion endpoint). */
    private String secretKey;

    public boolean enabled() { return enabled; }
    public String baseUrl() { return baseUrl; }
    public String publicKey() { return publicKey; }
    public String secretKey() { return secretKey; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
}
