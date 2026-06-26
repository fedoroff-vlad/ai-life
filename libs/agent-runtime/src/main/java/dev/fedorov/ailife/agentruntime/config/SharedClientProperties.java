package dev.fedorov.ailife.agentruntime.config;

/**
 * The base URLs of the platform services every agent's shared {@code agent-runtime} clients talk to —
 * {@code profile-service}, {@code notifier-service}, {@code memory-service}. Each agent's
 * {@code *AgentProperties} implements this (the getters already match), so {@link AgentRuntimeConfig}
 * can build the three {@code WebClient} beans
 * ({@code profileServiceWebClient} / {@code notifierWebClient} / {@code memoryServiceWebClient}) once
 * instead of every agent re-declaring identical {@code clone().baseUrl(...).build()} beans. Each agent
 * still owns the URL <i>values</i> (its own {@code @ConfigurationProperties} prefix / env vars) — only
 * the boilerplate {@code WebClient} wiring moved.
 */
public interface SharedClientProperties {

    String getProfileServiceUrl();

    String getNotifierUrl();

    String getMemoryServiceUrl();
}
