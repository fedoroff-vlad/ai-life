# syntax=docker/dockerfile:1.7
# Build context: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY domains/calendar/skills/ domains/calendar/skills/
COPY domains/calendar/calendar-agent/ domains/calendar/calendar-agent/
RUN --mount=type=cache,target=/root/.m2     mvn -B -ntp -pl domains/calendar/calendar-agent -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/domains/calendar/calendar-agent/target/calendar-agent.jar /app/app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
