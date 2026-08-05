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
COPY docker-entrypoint.sh /application/docker-entrypoint.sh
RUN chmod +x /application/docker-entrypoint.sh
RUN useradd -ms /bin/bash spring-user
USER spring-user
WORKDIR /application
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

ENTRYPOINT ["/application/docker-entrypoint.sh"]
CMD ["java", "org.springframework.boot.loader.launch.JarLauncher"]
