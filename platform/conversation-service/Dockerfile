# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2     mvn -B -ntp -pl platform/conversation-service -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/platform/conversation-service/target/conversation-service.jar /app/app.jar
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
