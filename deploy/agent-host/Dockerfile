# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this host + its co-hosted agents from the full reactor.
#
# ADR-0006 / #584 slice 3r: ONE container, ONE JVM, six agent contexts — the resident Agent-hot host.
# It replaces the six separate *-agent containers; each context keeps its own port, so the
# *_AGENT_URL values the orchestrator uses are unchanged (compose resolves the old service names to
# this container via network aliases).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl deploy/agent-host -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# No native dependencies: all six agents are LLM-bound (HTTP to llm-gateway and the MCPs), and none
# of their own Dockerfiles installs anything. Plain JRE, unlike domain-mcp-host's tesseract+ffmpeg.
COPY --from=build /src/deploy/agent-host/target/agent-host.jar /app/app.jar
# calendar · finance · tasks · researcher · notes · coordinator — the ports their own ymls declare.
EXPOSE 8086 8093 8096 8099 8118 8119
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
