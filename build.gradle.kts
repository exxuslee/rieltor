plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "com.rieltor"
version = "0.1.0"

val defaultTdlightNative = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "windows_amd64"
} else {
    "linux_amd64_gnu_ssl3"
}
val tdlightNativeClassifier = providers.gradleProperty("tdlightNativeClassifier")
    .orElse(defaultTdlightNative)

application {
    mainClass.set("com.rieltor.web.ApplicationKt")
}

repositories {
    mavenCentral()
    maven { url = uri("https://mvn.mchv.eu/repository/mchv/") }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)

    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dotenv.kotlin)
    implementation(libs.sqlite.jdbc)
    implementation(libs.imageio.webp)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(platform("it.tdlight:tdlight-java-bom:3.4.0+td.1.8.26"))
    implementation("it.tdlight:tdlight-java")
    implementation("it.tdlight:tdlight-natives") {
        artifact { classifier = tdlightNativeClassifier.get() }
    }

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    jvmToolchain(21)
}
