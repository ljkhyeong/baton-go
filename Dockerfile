# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
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

COPY domain/src/main domain/src/main
COPY application/src/main application/src/main
COPY adapter-in-web/src/main adapter-in-web/src/main
COPY adapter-out-persistence/src/main adapter-out-persistence/src/main
COPY adapter-out-external/src/main adapter-out-external/src/main
COPY bootstrap/src/main bootstrap/src/main

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :bootstrap:bootJar

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime

RUN addgroup -S -g 10001 batongo \
    && adduser -S -D -H -u 10001 -G batongo batongo

WORKDIR /opt/baton-go
COPY --from=build --chmod=0444 \
    /workspace/bootstrap/build/libs/baton-go.jar ./baton-go.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/opt/baton-go/baton-go.jar"]
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -q -T 2 -O /dev/null http://127.0.0.1:8080/readyz || exit 1
