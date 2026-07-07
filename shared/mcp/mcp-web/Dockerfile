# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl shared/mcp/mcp-web -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# yt-dlp for transcribe_video — the self-contained linux binary (PyInstaller bundle, no system
# Python needed). The transcript engine shells out to it; swappable behind VideoTranscriptEngine.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates wget \
    && wget -q https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux -O /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/shared/mcp/mcp-web/target/mcp-web.jar /app/app.jar
EXPOSE 8098
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
