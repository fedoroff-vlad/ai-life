# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl domains/creator/mcp-creator -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/domains/creator/mcp-creator/target/mcp-creator.jar /app/app.jar
EXPOSE 8108
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
