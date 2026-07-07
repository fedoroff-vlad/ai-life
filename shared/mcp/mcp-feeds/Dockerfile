# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl shared/mcp/mcp-feeds -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# No native deps and no backing container — feeds come from RSS URLs / public Telegram over HTTPS
# (the JRE image ships the CA certificates needed for the TLS call).
COPY --from=build /src/shared/mcp/mcp-feeds/target/mcp-feeds.jar /app/app.jar
EXPOSE 8112
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
