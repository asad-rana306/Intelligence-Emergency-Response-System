# ═══════════════════════════════════════════════════════
#  IERS API Gateway — Multi-Stage Docker Build
# ═══════════════════════════════════════════════════════
#  Stage 1: Build the fat JAR with Maven
#  Stage 2: Run on a minimal JRE image
# ═══════════════════════════════════════════════════════

# ── Build Stage ──
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies first (only re-downloads if pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# ── Runtime Stage ──
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Add a non-root user for security
RUN addgroup -S iers && adduser -S iers -G iers
USER iers

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# JVM tuning for container environments
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
