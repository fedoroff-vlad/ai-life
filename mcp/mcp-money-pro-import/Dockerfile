# syntax=docker/dockerfile:1.7
# Build context: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY mcp/mcp-money-pro-import/ mcp/mcp-money-pro-import/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl mcp/mcp-money-pro-import -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/mcp/mcp-money-pro-import/target/mcp-money-pro-import.jar /app/app.jar
EXPOSE 8094
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
