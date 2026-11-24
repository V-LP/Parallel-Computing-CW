FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY server/pom.xml server/
COPY cli/pom.xml cli/
COPY server/src server/src
RUN mvn -B -q -pl server -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/server/target/server.jar /app/server.jar

ENV PORT=8080 \
    DATA_DIR=/data
EXPOSE 8080
# jvm heap takes most of the container memory
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]
