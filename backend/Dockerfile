FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:17.0.13_11-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --system --gid app --home-dir /app app && mkdir -p /app && chown app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /app/target/si-cost-1.0.0.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
