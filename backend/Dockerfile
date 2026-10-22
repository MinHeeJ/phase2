FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17.0.14_7-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && groupadd --system sicost && useradd --system --gid sicost --home-dir /app sicost
WORKDIR /app
COPY --from=build --chown=sicost:sicost /app/target/sicost.jar ./sicost.jar
USER sicost
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/sicost.jar"]
