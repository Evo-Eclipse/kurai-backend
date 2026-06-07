# syntax=docker/dockerfile:1

FROM docker.io/eclipse-temurin:25-jdk-resolute AS build
WORKDIR /app

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY domain/ domain/
COPY application/ application/
COPY infrastructure/ infrastructure/
COPY server/ server/

RUN ./gradlew :server:installDist :server:buildFatJar --no-daemon -x test -x smokeTest -x ktlintCheck
RUN MODULES="$(jdeps --ignore-missing-deps --multi-release 25 --print-module-deps \
        server/build/libs/*-all.jar)" \
    && jlink \
        --add-modules "${MODULES}" \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-6 \
        --output /opt/jre

FROM docker.io/ubuntu:resolute AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /opt/jre /opt/jre
COPY --from=build /app/server/build/install/server /opt/kurai

RUN chmod +x /opt/kurai/bin/server

ENV JAVA_HOME=/opt/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"

WORKDIR /opt/kurai
EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=1s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/health/ready || exit 1

ENTRYPOINT ["/opt/kurai/bin/server"]
