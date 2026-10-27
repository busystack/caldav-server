FROM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS java

FROM maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e AS build
COPY --from=java /opt/java/openjdk /opt/java/openjdk
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM java AS runtime
ENV SERVER_ADDRESS=0.0.0.0
RUN groupadd --gid 10001 busystack && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin busystack
WORKDIR /opt/busystack
COPY --from=build --chown=10001:10001 /workspace/target/*.jar application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-Djava.io.tmpdir=/tmp", "-jar", "/opt/busystack/application.jar"]
