# syntax=docker/dockerfile:1.7
# Build context: repo root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY libs/ libs/
COPY domains/finance/mcp-finance/ domains/finance/mcp-finance/
RUN --mount=type=cache,target=/root/.m2     mvn -B -ntp -pl domains/finance/mcp-finance -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/domains/finance/mcp-finance/target/mcp-finance.jar /app/app.jar
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
