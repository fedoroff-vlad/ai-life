# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY platform/scheduler-service/ platform/scheduler-service/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl platform/scheduler-service -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/platform/scheduler-service/target/scheduler-service.jar /app/app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
