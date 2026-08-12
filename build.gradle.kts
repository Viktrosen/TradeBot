plugins {
    kotlin("jvm") version "2.0.21"  // ← Исправлено: стабильная версия
    kotlin("plugin.spring") version "2.0.21"  // ← Исправлено: стабильная версия
    id("org.springframework.boot") version "3.3.5"  // ← Исправлено: стабильная версия вместо SNAPSHOT
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("plugin.jpa") version "2.0.21"  // ← Исправлено: версия должна совпадать с kotlin
    id("org.flywaydb.flyway") version "10.20.0"  // ← Исправлено: совместимая версия
}

group = "ru.bolotov"
version = "0.1.5-SNAPSHOT"
description = "TradeBot"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.postgresql:postgresql:42.7.3")
        classpath("org.flywaydb:flyway-database-postgresql:10.20.0")
    }
}

repositories {
    mavenCentral()
    // maven { url = uri("https://repo.spring.io/snapshot") }  // ← Убрать, используем стабильные версии
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.8.1")

    implementation("ru.tinkoff.piapi:java-sdk-spring-boot-starter:1.31")
    implementation("ru.tinkoff.piapi:java-sdk-strategy:1.31")

    implementation("io.grpc:grpc-netty-shaded:1.64.0")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

flyway {
    val dbHost = System.getenv("DB_HOST") ?: "localhost"
    val dbPort = System.getenv("DB_PORT") ?: "5432"
    val dbName = System.getenv("DB_NAME") ?: "postgres"  // ← Изменить на postgres
    val dbUser = System.getenv("DB_USERNAME") ?: "postgres"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "konoha"  // ← Изменить на konoha

    url = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
    user = dbUser
    password = dbPassword
    driver = "org.postgresql.Driver"
}

tasks.withType<Test> {
    useJUnitPlatform()
}