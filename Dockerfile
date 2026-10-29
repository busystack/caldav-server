FROM eclipse-temurin:25-jdk@sha256:3ab7fc8d0b4ade8fef96903fb36767c1dbb4fd1535dcfad85b59c17afbbb5857 AS java

FROM maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e AS build
COPY --from=java /opt/java/openjdk /opt/java/openjdk
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml LICENSE NOTICE THIRD-PARTY-NOTICES ./
COPY licenses licenses
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM java AS runtime
ARG SOURCE_REVISION
LABEL org.opencontainers.image.title="BusyStack CalDAV Server" \
      org.opencontainers.image.description="Independent calendar and task server" \
      org.opencontainers.image.version="0.0.1-SNAPSHOT" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/busystack/caldav-server" \
      org.opencontainers.image.revision="${SOURCE_REVISION}"
ENV SERVER_ADDRESS=0.0.0.0
RUN groupadd --gid 10001 busystack && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin busystack
WORKDIR /opt/busystack
COPY --from=build --chown=10001:10001 /workspace/target/*.jar application.jar
COPY --from=build --chown=10001:10001 /workspace/LICENSE /workspace/NOTICE /workspace/THIRD-PARTY-NOTICES /opt/busystack/licenses/
COPY --from=build --chown=10001:10001 /workspace/target/classes/META-INF/sbom/application.cdx.json /opt/busystack/licenses/runtime-dependency-inventory.json
COPY --from=build --chown=10001:10001 /workspace/target/classes/META-INF/licenses/ /opt/busystack/licenses/third-party/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-Djava.io.tmpdir=/tmp", "-jar", "/opt/busystack/application.jar"]
