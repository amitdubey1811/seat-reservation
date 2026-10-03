# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime --------------------------------------------------------------
# Alpine rather than the Ubuntu-based JRE: the base is roughly a quarter of the size, and
# on a free-tier host a smaller image means a faster pull and a faster cold start.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Alpine's BusyBox tools, not Debian's groupadd/useradd.
RUN addgroup -S app && adduser -S -G app -H app

# --chown on the COPY itself, not a following `RUN chown -R`. The latter rewrites every
# file and so stores a second complete copy of the 60 MB jar in its own layer.
COPY --from=build --chown=app:app /build/target/seat-reservation-*.jar /app/app.jar
USER app

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
