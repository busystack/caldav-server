#!/usr/bin/env bash
set -euo pipefail

# Use the same immutable Temurin distribution as the application image. setup-java's
# SemVer input cannot represent this four-part Java maintenance release exactly.
: "${RUNNER_TEMP:?RUNNER_TEMP must be set by the GitHub runner}"
: "${GITHUB_ENV:?GITHUB_ENV must be set by the GitHub runner}"
: "${GITHUB_PATH:?GITHUB_PATH must be set by the GitHub runner}"
jdk_image='eclipse-temurin:25-jdk@sha256:3ab7fc8d0b4ade8fef96903fb36767c1dbb4fd1535dcfad85b59c17afbbb5857'
jdk_directory=$(mktemp -d "${RUNNER_TEMP}/busystack-jdk.XXXXXXXX")
jdk_container=''
cleanup() {
  if [[ -n "$jdk_container" ]]; then
    docker container rm "$jdk_container" > /dev/null
  fi
}
trap cleanup EXIT

docker info > /dev/null
docker pull "$jdk_image"
jdk_container=$(docker container create --network none --entrypoint /bin/true "$jdk_image")
docker container cp "${jdk_container}:/opt/java/openjdk/." "$jdk_directory"

python3 - "$jdk_directory" <<'PY'
from pathlib import Path
import subprocess
import sys

jdk = Path(sys.argv[1])
release = dict(line.split('=', 1) for line in (jdk / 'release').read_text().splitlines() if '=' in line)
expected = {
    'IMPLEMENTOR': 'Eclipse Adoptium',
    'IMPLEMENTOR_VERSION': 'Temurin-25.0.4.1+1',
    'JAVA_VERSION': '25.0.4.1',
    'JAVA_RUNTIME_VERSION': '25.0.4.1+1-LTS',
    'SEMANTIC_VERSION': '25.0.4.1+1',
    'IMAGE_TYPE': 'JDK',
}
for name, value in expected.items():
    if release.get(name, '').strip('"') != value:
        raise SystemExit(f'Unexpected pinned JDK release field: {name}')
result = subprocess.run([str(jdk / 'bin/java'), '-XshowSettings:properties', '-version'],
                        check=True, capture_output=True, text=True)
properties = dict(line.lstrip().split(' = ', 1) for line in result.stderr.splitlines() if ' = ' in line)
if properties.get('java.runtime.version') != expected['JAVA_RUNTIME_VERSION']:
    raise SystemExit('Pinned JDK executable does not match the selected runtime version')
if properties.get('java.vendor') != expected['IMPLEMENTOR']:
    raise SystemExit('Pinned JDK executable does not match the selected distribution')
subprocess.run([str(jdk / 'bin/javac'), '-version'], check=True)
print(f"Selected Eclipse Temurin {expected['SEMANTIC_VERSION']} from the pinned application JDK image")
PY

printf 'JAVA_HOME=%s\n' "$jdk_directory" >> "$GITHUB_ENV"
printf '%s\n' "$jdk_directory/bin" >> "$GITHUB_PATH"
