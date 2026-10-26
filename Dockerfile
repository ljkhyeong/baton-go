# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM eclipse-temurin:21-jdk-alpine@sha256:fd52084c35bbac78890d95d57707d31ffc9d7a3324199e89c06be2a9dc71bfa0 AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY domain domain
COPY application application
COPY adapter-in-web adapter-in-web
COPY adapter-out-persistence adapter-out-persistence
COPY adapter-out-external adapter-out-external
COPY bootstrap bootstrap

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :bootstrap:bootJar

FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088 AS runtime

RUN addgroup -S -g 10001 batongo \
    && adduser -S -D -H -u 10001 -G batongo batongo

WORKDIR /opt/baton-go
COPY --from=build --chmod=0444 \
    /workspace/bootstrap/build/libs/baton-go.jar ./baton-go.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/opt/baton-go/baton-go.jar"]
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -q -T 2 -O /dev/null http://127.0.0.1:8080/startupz || exit 1
