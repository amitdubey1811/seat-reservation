# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app

COPY --from=build /build/target/seat-reservation-*.jar /app/app.jar
RUN chown -R app:app /app
USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
