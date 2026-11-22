# Stage 1: build the fat-jar with Maven.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# The root pom lists the cli module, so its pom must be present (its sources are not built).
COPY pom.xml ./
COPY server/pom.xml server/
COPY cli/pom.xml cli/
COPY server/src server/src
RUN mvn -B -q -pl server -am -DskipTests package

# Stage 2: small runtime image.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/server/target/server.jar /app/server.jar

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
