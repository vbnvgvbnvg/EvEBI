# Multi-stage build for batch-etl
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy root pom and wrapper
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw mvnw

# Copy module POMs for layer caching
COPY esi-client/pom.xml esi-client/
COPY ingestion-poller/pom.xml ingestion-poller/
COPY worker-service/pom.xml worker-service/
COPY batch-etl/pom.xml batch-etl/

# Cache dependencies
RUN ./mvnw dependency:go-offline -B -pl batch-etl -am || true

# Copy required module source trees
COPY esi-client/ esi-client/
COPY batch-etl/ batch-etl/

# Build batch-etl executable jar
RUN ./mvnw clean package -pl batch-etl -am -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: non-root user and group
RUN addgroup -g 10001 -S evebi && \
    adduser -u 10001 -S evebi -G evebi

WORKDIR /app

# Copy artifact from builder
COPY --from=builder --chown=evebi:evebi /build/batch-etl/target/batch-etl-*.jar /app/app.jar

USER 10001:10001

EXPOSE 8083

# exec java ensures PID 1 signal forwarding for unhindered SIGTERM handling
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
