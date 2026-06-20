# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl shared/mcp/mcp-web -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/shared/mcp/mcp-web/target/mcp-web.jar /app/app.jar
EXPOSE 8098
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
