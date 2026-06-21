# syntax=docker/dockerfile:1.7
# Build context: repo root. Builds this module + its libs from the full reactor.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl shared/mcp/mcp-market-data -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
# No native deps and no backing container — quotes come from Stooq over public HTTPS
# (the JRE image ships the CA certificates needed for the TLS call).
COPY --from=build /src/shared/mcp/mcp-market-data/target/mcp-market-data.jar /app/app.jar
EXPOSE 8100
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
