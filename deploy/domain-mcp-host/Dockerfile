# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this host + its co-hosted modules from the full reactor.
#
# ADR-0006 / #584 slice 3o: ONE container, ONE JVM, five MCP contexts — the resident Domain-MCP-hot
# host. It replaces the five separate mcp-* containers; each context keeps its own port and its own
# MCP server, so agents' /internal/* URLs are unchanged (compose resolves the old service names to
# this container via network aliases).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl deploy/domain-mcp-host -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
# The image carries the UNION of its members' native dependencies — today that is only
# mcp-media-processing's: tesseract (+eng/rus data) for the `ocr` tool and ffmpeg (+ffprobe) for the
# `frames` keyframe extractor. The other four members need nothing native.
# Slice 3l (folding mcp-media-fetch in) will add yt-dlp here.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-eng tesseract-ocr-rus ffmpeg \
    && rm -rf /var/lib/apt/lists/*
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata
COPY --from=build /src/deploy/domain-mcp-host/target/domain-mcp-host.jar /app/app.jar
# caldav · finance · tasks · media-processing · web — the ports their own ymls declare.
EXPOSE 8090 8092 8095 8097 8098
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
