# syntax=docker/dockerfile:1.7
# Build context: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY domains/calendar/mcp-ics-import/ domains/calendar/mcp-ics-import/
RUN --mount=type=cache,target=/root/.m2     mvn -B -ntp -pl domains/calendar/mcp-ics-import -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/domains/calendar/mcp-ics-import/target/mcp-ics-import.jar /app/app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
