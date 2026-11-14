plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

kotlin {
  jvmToolchain(17)
}

application {
  mainClass.set("com.pizzora.backend.ApplicationKt")
}

dependencies {
  implementation(project(":shared:contracts"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.ktor.server.call.id)
  implementation(libs.ktor.server.default.headers)
  implementation(libs.ktor.server.auth)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logback.classic)
  implementation(libs.logstash.logback.encoder)
  implementation(libs.postgresql)
  implementation(libs.hikaricp)
  implementation(libs.flyway.core)
  implementation(libs.flyway.database.postgresql)
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  implementation(libs.exposed.java.time)
  implementation(libs.bcprov)
  implementation(libs.java.jwt)
  implementation(libs.lettuce.core)
  implementation(libs.angus.mail)

  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.client.websockets)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.junit)
  testImplementation(libs.embedded.postgres)
}

tasks.register<JavaExec>("runWorker") {
  group = "application"
  description = "Runs the PIZZORA background worker process"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.pizzora.backend.worker.WorkerMainKt")
}
