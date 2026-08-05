FROM bellsoft/liberica-openjdk-debian:17 AS builder
WORKDIR /application
COPY . .
RUN chmod +x gradlew
# Убираем --mount, добавляем --no-daemon
RUN ./gradlew clean build -x test --no-daemon

FROM bellsoft/liberica-openjre-debian:17 AS layers
WORKDIR /application
COPY --from=builder /application/build/libs/*-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM bellsoft/liberica-openjre-debian:17
VOLUME /tmp
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ca-certificates-java openssl \
    && update-ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_TOOL_OPTIONS="-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true -Dio.netty.handler.ssl.noOpenSsl=true"
RUN mkdir -p /application \
    && printf '%s\n' \
    '#!/bin/sh' \
    'set -eu' \
    'TRUSTSTORE_PATH="${JAVA_SSL_TRUSTSTORE:-/tmp/tradebot-truststore.jks}"' \
    'TRUSTSTORE_PASSWORD="${JAVA_SSL_TRUSTSTORE_PASSWORD:-changeit}"' \
    'CERT_DIR="/tmp/tradebot-certs"' \
    'if [ "${TINKOFF_TLS_IMPORT_ENABLED:-true}" = "true" ]; then' \
    '  mkdir -p "$CERT_DIR"' \
    '  if [ ! -f "$TRUSTSTORE_PATH" ]; then' \
    '    cp "$JAVA_HOME/lib/security/cacerts" "$TRUSTSTORE_PATH"' \
    '  fi' \
    '  for host in sandbox-invest-public-api.tbank.ru invest-public-api.tbank.ru; do' \
    '    cert_file="$CERT_DIR/$host.crt"' \
    '    echo | openssl s_client -servername "$host" -connect "$host:443" 2>/dev/null | openssl x509 -outform PEM > "$cert_file" || true' \
    '    if [ -s "$cert_file" ]; then' \
    '      alias_name="tbank-$host"' \
    '      keytool -delete -alias "$alias_name" -keystore "$TRUSTSTORE_PATH" -storepass "$TRUSTSTORE_PASSWORD" >/dev/null 2>&1 || true' \
    '      keytool -importcert -noprompt -trustcacerts -alias "$alias_name" -file "$cert_file" -keystore "$TRUSTSTORE_PATH" -storepass "$TRUSTSTORE_PASSWORD" >/dev/null 2>&1 || true' \
    '    fi' \
    '  done' \
    '  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=$TRUSTSTORE_PATH -Djavax.net.ssl.trustStorePassword=$TRUSTSTORE_PASSWORD"' \
    'fi' \
    'exec "$@"' \
    > /application/docker-entrypoint.sh \
    && chmod +x /application/docker-entrypoint.sh
RUN useradd -ms /bin/bash spring-user
USER spring-user
WORKDIR /application
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

ENTRYPOINT ["/application/docker-entrypoint.sh"]
CMD ["java", "org.springframework.boot.loader.launch.JarLauncher"]
