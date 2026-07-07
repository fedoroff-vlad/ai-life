# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl shared/mcp/mcp-image-gen -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/shared/mcp/mcp-image-gen/target/mcp-image-gen.jar /app/app.jar
EXPOSE 8103
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
