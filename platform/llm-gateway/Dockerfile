# syntax=docker/dockerfile:1.7
#
# Build context expected: repo root.
# Build:  docker build -f platform/llm-gateway/Dockerfile -t ai-life/llm-gateway .
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY platform/llm-gateway/ platform/llm-gateway/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl platform/llm-gateway -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/platform/llm-gateway/target/llm-gateway.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
