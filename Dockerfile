# syntax=docker/dockerfile:1.7
# ---- build -------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/bus-stop-*.jar extract --layers --launcher --destination /extracted

# ---- run ---------------------------------------------------------------------
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# API under /api; health probes at /api/actuator/health/{liveness,readiness}
EXPOSE 8097
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
