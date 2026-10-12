# Two stages, so `docker compose up --build` works on a machine with only Docker: no Java or Maven needed.

# 1. Build the jar. Tests are skipped here: they need Docker themselves and run with ./mvnw verify instead.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
# Dependencies first, in their own layer, so a code change doesn't download them all again.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

# 2. Run it on a Java runtime only, as a non-root user.
FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 vitallink
WORKDIR /app
COPY --from=build /src/target/readings-service-*.jar app.jar
USER vitallink
EXPOSE 8080
# Let the JVM use most of the container's memory limit instead of the small default share.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
