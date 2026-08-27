plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "com.rieltor"
version = "0.1.0"

application {
    mainClass.set("com.rieltor.web.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)

    implementation(libs.logback.classic)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dotenv.kotlin)
    implementation(libs.sqlite.jdbc)
    implementation(libs.telegrambots.longpolling)
    implementation(libs.telegrambots.client)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    jvmToolchain(21)
}

tasks.register<JavaExec>("importLocalSecrets") {
    group = "application"
    description = "Imports legacy autoposter credentials into the ignored local SQLite database"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rieltor.tools.ImportLocalSecretsKt")
    val legacyDir = providers.gradleProperty("legacyAutoposterDir")
        .orElse("D:/Android/PRO/autoposter")
    val targetDb = providers.gradleProperty("targetDatabase")
        .orElse("data/rieltor.db")
    args(legacyDir.get(), targetDb.get())
}

tasks.register<JavaExec>("setLocalSecret") {
    group = "application"
    description = "Updates one supported SQLite secret from the SECRET_VALUE environment variable"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rieltor.tools.SetLocalSecretKt")
    val secretName = providers.gradleProperty("secretName")
    val targetDb = providers.gradleProperty("targetDatabase")
        .orElse("data/rieltor.db")
    args(secretName.get(), targetDb.get())
}
