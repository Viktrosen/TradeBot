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
    '    bundle_file="$CERT_DIR/$host.pem"' \
    '    echo "Импорт TLS-сертификатов для $host"' \
    '    echo | openssl s_client -showcerts -servername "$host" -connect "$host:443" > "$bundle_file" 2>/dev/null || true' \
    '    awk -v dir="$CERT_DIR" -v host="$host" '"'"'/-----BEGIN CERTIFICATE-----/ { n++; file=sprintf("%s/%s-%02d.crt", dir, host, n) } file { print > file } /-----END CERTIFICATE-----/ { close(file); file="" }'"'"' "$bundle_file"' \
    '    index=0' \
    '    for cert_file in "$CERT_DIR/$host"-*.crt; do' \
    '      [ -s "$cert_file" ] || continue' \
    '      index=$((index + 1))' \
    '      alias_name="tbank-$host-$index"' \
    '      subject="$(openssl x509 -in "$cert_file" -noout -subject 2>/dev/null || true)"' \
    '      issuer="$(openssl x509 -in "$cert_file" -noout -issuer 2>/dev/null || true)"' \
    '      echo "Добавляем сертификат $alias_name: $subject / $issuer"' \
    '      keytool -delete -alias "$alias_name" -keystore "$TRUSTSTORE_PATH" -storepass "$TRUSTSTORE_PASSWORD" >/dev/null 2>&1 || true' \
    '      keytool -importcert -noprompt -trustcacerts -alias "$alias_name" -file "$cert_file" -keystore "$TRUSTSTORE_PATH" -storepass "$TRUSTSTORE_PASSWORD"' \
    '    done' \
    '    if [ "$index" -eq 0 ]; then' \
    '      echo "Не удалось получить сертификаты для $host"' \
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
