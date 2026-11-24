# Console client in a container: docker compose run --rm cli "white whale"
# Build context is the repository root (see docker-compose.yml).

# Stage 1: build the fat-jar with Maven.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# The root pom lists the server module, so its pom must be present (its sources are not built).
COPY pom.xml ./
COPY server/pom.xml server/
COPY cli/pom.xml cli/
COPY cli/src cli/src
RUN mvn -B -q -pl cli -am -DskipTests package

# Stage 2: small runtime image.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/cli/target/search-cli.jar /app/search-cli.jar

# UTF-8 locale: Java decodes the command-line arguments with it, so Cyrillic queries survive.
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    SEARCH_URL=http://server:8080
ENTRYPOINT ["java", "-jar", "/app/search-cli.jar"]
