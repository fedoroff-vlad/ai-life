# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl shared/mcp/mcp-media-processing -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# Native OCR engine (Tess4J in-image): tesseract + English/Russian language data.
# The engine resolves tessdata from TESSDATA_PREFIX (set below) or a path probe.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-eng tesseract-ocr-rus \
    && rm -rf /var/lib/apt/lists/*
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata
COPY --from=build /src/shared/mcp/mcp-media-processing/target/mcp-media-processing.jar /app/app.jar
EXPOSE 8097
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
