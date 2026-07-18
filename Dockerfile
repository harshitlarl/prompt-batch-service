# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source for faster rebuilds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser

COPY --from=build /build/target/prompt-batch-service.jar ./app.jar
COPY config ./config

RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080 8081

# APP_ENV selects config/config-<env>.yml (local | stage | prod); override at
# `docker run`/compose time with -e APP_ENV=stage, no rebuild required.
ENV APP_ENV=local

ENTRYPOINT ["sh", "-c", "java -jar app.jar server config/config-${APP_ENV}.yml"]
