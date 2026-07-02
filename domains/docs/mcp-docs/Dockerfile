# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl domains/docs/mcp-docs -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/domains/docs/mcp-docs/target/mcp-docs.jar /app/app.jar
EXPOSE 8116
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
