# Build stage: full JDK + Maven wrapper. Dependencies are a separate layer so code edits rebuild fast.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests need PostgreSQL (Testcontainers); they run with ./mvnw test, not inside the image build.
RUN sh ./mvnw -B -q -DskipTests package

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 ledger
WORKDIR /app
COPY --from=build /workspace/target/ledger-service-*.jar app.jar
USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
