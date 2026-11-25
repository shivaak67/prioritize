# Build stage — compile Spring Boot JAR with Maven wrapper + JDK 21
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -DskipTests package \
    && JAR=$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1) \
    && cp "$JAR" /app/app.jar

# Runtime stage — JRE 21, non-root
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring \
    && apk add --no-cache curl \
    && mkdir -p /app/logs && chown spring:spring /app/logs && chmod 700 /app/logs

USER spring:spring

COPY --from=build /app/app.jar ./app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
