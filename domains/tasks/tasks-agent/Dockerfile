# syntax=docker/dockerfile:1.7
# Build context: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY domains/tasks/skills/ domains/tasks/skills/
COPY domains/tasks/tasks-agent/ domains/tasks/tasks-agent/
RUN --mount=type=cache,target=/root/.m2     mvn -B -ntp -pl domains/tasks/tasks-agent -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/domains/tasks/tasks-agent/target/tasks-agent.jar /app/app.jar
EXPOSE 8096
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
