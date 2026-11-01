# Test fixture only: the official UBI9 runtime requires x86-64-v2.
# Copy the identical pinned Keycloak distribution onto the Java 25 runtime
# already qualified for this workspace's older CPU. No server code is changed.
FROM quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b AS distribution
FROM eclipse-temurin:25-jdk@sha256:3ab7fc8d0b4ade8fef96903fb36767c1dbb4fd1535dcfad85b59c17afbbb5857
COPY --from=distribution --chown=1000:1000 /opt/keycloak /opt/keycloak
ENV KC_RUN_IN_CONTAINER=true
USER 1000:1000
WORKDIR /opt/keycloak
EXPOSE 8443
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
