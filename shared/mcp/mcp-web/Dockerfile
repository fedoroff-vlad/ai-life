# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl shared/mcp/mcp-web -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# No native deps and no backing container — web_search/fetch_url are HTTP calls (the JRE image ships
# the CA certificates). Video transcription moved to mcp-media-fetch (which bundles yt-dlp).
COPY --from=build /src/shared/mcp/mcp-web/target/mcp-web.jar /app/app.jar
EXPOSE 8098
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
