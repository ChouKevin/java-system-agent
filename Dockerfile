FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
    && test -f target/java-system-agent-1.0-SNAPSHOT.jar \
    && cp target/java-system-agent-1.0-SNAPSHOT.jar /tmp/app.jar

FROM eclipse-temurin:21-jre-alpine

ENV LOG_DIR=/app/logs

RUN addgroup -S -g 10001 agent \
    && adduser -S -D -H -u 10001 -G agent agent \
    && mkdir -p /app/logs/archived \
    && chown -R 10001:10001 /app \
    && chmod 0755 /app/logs /app/logs/archived

WORKDIR /app

COPY --from=build --chown=10001:10001 /tmp/app.jar /app/app.jar

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
