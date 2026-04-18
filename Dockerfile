FROM bellsoft/liberica-openjdk-debian:17 AS builder
WORKDIR /application
COPY . .
RUN chmod +x gradlew
# Убираем --mount и добавляем --no-daemon для корректного завершения
RUN ./gradlew clean build -x test --no-daemon

FROM bellsoft/liberica-openjre-debian:17 AS layers
WORKDIR /application
# Копируем ТОЛЬКО исполняемый bootJar (не plain)
COPY --from=builder /application/build/libs/*-SNAPSHOT.jar app.jar
# Извлекаем слои
RUN java -Djarmode=layertools -jar app.jar extract

FROM bellsoft/liberica-openjre-debian:17
VOLUME /tmp
RUN useradd -ms /bin/bash spring-user
USER spring-user
WORKDIR /application
COPY --from=layers /application/dependencies/ ./
COPY --from=layers /application/spring-boot-loader/ ./
COPY --from=layers /application/snapshot-dependencies/ ./
COPY --from=layers /application/application/ ./

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]