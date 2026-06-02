# syntax=docker/dockerfile:1.7
# Build context expected: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY platform/profile-service/ platform/profile-service/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl platform/profile-service -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/platform/profile-service/target/profile-service.jar /app/app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
