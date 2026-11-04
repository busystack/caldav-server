# Use python3 scripts/build-image.py to supply the verified Maven artifact.
FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS runtime
ARG APPLICATION_VERSION
ARG SOURCE_REVISION
WORKDIR /opt/busystack
RUN ["/bin/bash", "-ec", "[[ ${APPLICATION_VERSION:-} =~ ^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$ && ${SOURCE_REVISION:-} =~ ^[0-9a-f]{40}$ ]] || { echo 'Build with python3 scripts/build-image.py: valid Maven version and Git revision are required' >&2; exit 1; }"]
COPY .image-input/ /tmp/busystack-image-input/
RUN ["/bin/bash", "-ec", "cd /tmp/busystack-image-input; [[ ${APPLICATION_VERSION:-} =~ ^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$ && ${SOURCE_REVISION:-} =~ ^[0-9a-f]{40}$ ]] || { echo 'Build with python3 scripts/build-image.py: valid Maven version and Git revision are required' >&2; exit 1; }; test \"$(cat version)\" = \"$APPLICATION_VERSION\"; test \"$(cat revision)\" = \"$SOURCE_REVISION\"; sha256sum --check SHA256SUMS; mkdir extracted; cd extracted; jar xf ../application.jar META-INF/MANIFEST.MF META-INF/maven/org.busystack/caldav-server/pom.properties; tr -d '\\r' < META-INF/MANIFEST.MF | grep -Fx \"Implementation-Version: $APPLICATION_VERSION\"; grep -Fx \"version=$APPLICATION_VERSION\" META-INF/maven/org.busystack/caldav-server/pom.properties; grep -Fx 'groupId=org.busystack' META-INF/maven/org.busystack/caldav-server/pom.properties; grep -Fx 'artifactId=caldav-server' META-INF/maven/org.busystack/caldav-server/pom.properties; cd ..; mv application.jar /opt/busystack/application.jar; mkdir /opt/busystack/licenses; mv LICENSE NOTICE THIRD-PARTY-NOTICES runtime-dependency-inventory.json third-party /opt/busystack/licenses/; rm -rf /tmp/busystack-image-input"]
LABEL org.opencontainers.image.title="BusyStack CalDAV Server" \
      org.opencontainers.image.description="Independent calendar and task server" \
      org.opencontainers.image.version="${APPLICATION_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/busystack/caldav-server" \
      org.opencontainers.image.revision="${SOURCE_REVISION}"
ENV SERVER_ADDRESS=0.0.0.0
RUN groupadd --gid 10001 busystack && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin busystack && chown -R 10001:10001 /opt/busystack
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=40.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/opt/busystack/application.jar"]
