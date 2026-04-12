FROM bellsoft/liberica-openjdk-debian:17 AS builder
WORKDIR /application
COPY . .
# Даем права на выполнение gradlew
RUN chmod +x gradlew
# Сборка с Gradle (без тестов)
RUN --mount=type=cache,target=/root/.gradle ./gradlew clean build -x test

FROM bellsoft/liberica-openjre-debian:17 AS layers
WORKDIR /application
# Копируем собранный JAR
COPY --from=builder /application/build/libs/*.jar app.jar
# Извлекаем слои для оптимизации
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