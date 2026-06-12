# syntax=docker/dockerfile:1.7
# Build context: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY mcp/mcp-tasks/ mcp/mcp-tasks/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl mcp/mcp-tasks -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/mcp/mcp-tasks/target/mcp-tasks.jar /app/app.jar
EXPOSE 8095
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
