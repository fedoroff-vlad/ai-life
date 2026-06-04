# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY agents/calendar-agent/ agents/calendar-agent/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl agents/calendar-agent -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/agents/calendar-agent/target/calendar-agent.jar /app/app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
