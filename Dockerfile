# syntax=docker/dockerfile:1.7

ARG JAVA_BUILD_IMAGE=eclipse-temurin:21-jdk-alpine
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${JAVA_BUILD_IMAGE} AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY domain/build.gradle domain/build.gradle
COPY application/build.gradle application/build.gradle
COPY adapter-in-web/build.gradle adapter-in-web/build.gradle
COPY adapter-out-persistence/build.gradle adapter-out-persistence/build.gradle
COPY adapter-out-external/build.gradle adapter-out-external/build.gradle
COPY guard-tool/build.gradle guard-tool/build.gradle
COPY bootstrap/build.gradle bootstrap/build.gradle

RUN chmod 0755 gradlew

COPY domain/src domain/src
COPY application/src application/src
COPY adapter-in-web/src adapter-in-web/src
COPY adapter-out-persistence/src adapter-out-persistence/src
COPY adapter-out-external/src adapter-out-external/src
COPY bootstrap/src bootstrap/src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :bootstrap:bootJar \
    && cp bootstrap/build/libs/baton-go.jar /workspace/baton-go.jar

FROM ${JAVA_RUNTIME_IMAGE} AS runtime

RUN addgroup -S -g 10001 batongo \
    && adduser -S -D -H -u 10001 -G batongo batongo

WORKDIR /opt/baton-go
COPY --from=build --chown=0:0 --chmod=0444 /workspace/baton-go.jar ./baton-go.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
USER 10001:10001
EXPOSE 8080 8081
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/opt/baton-go/baton-go.jar"]
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -q -T 2 -O /dev/null http://127.0.0.1:8081/actuator/health || exit 1
