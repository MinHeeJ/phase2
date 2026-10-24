FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17.0.15_6-jre-alpine AS runtime
RUN addgroup -S application && adduser -S application -G application && apk add --no-cache curl
WORKDIR /app
COPY --from=build --chown=application:application /app/target/cost-management.jar ./application.jar
RUN mkdir -p /app/data/uploads && chown application:application /app/data/uploads
USER application
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/application.jar"]
