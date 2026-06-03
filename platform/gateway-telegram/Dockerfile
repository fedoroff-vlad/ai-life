# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY platform/gateway-telegram/ platform/gateway-telegram/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl platform/gateway-telegram -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/platform/gateway-telegram/target/gateway-telegram.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
