FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY server/pom.xml server/
COPY cli/pom.xml cli/
COPY cli/src cli/src
RUN mvn -B -q -pl cli -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/cli/target/search-cli.jar /app/search-cli.jar

# utf-8 locale for cyrillic arguments
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    SEARCH_URL=http://server:8080
ENTRYPOINT ["java", "-jar", "/app/search-cli.jar"]
