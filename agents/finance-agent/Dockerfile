# syntax=docker/dockerfile:1.7
# Build context: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY skills/ skills/
COPY agents/finance-agent/ agents/finance-agent/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl agents/finance-agent -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/agents/finance-agent/target/finance-agent.jar /app/app.jar
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
