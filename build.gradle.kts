import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper

plugins {
    kotlin("jvm") version "1.6.10"
    application
}

val kotlinVersion = plugins.getPlugin(KotlinPluginWrapper::class.java).kotlinPluginVersion

group = "me.vridosh"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    // implementation("com.google.inject:guice:4.0-beta5")

    implementation("org.jetbrains.kotlin:kotlin-reflect:${kotlinVersion}")

    implementation("io.vertx:vertx-core:4.2.3")
    implementation("io.vertx:vertx-web:4.2.3")
    implementation("io.vertx:vertx-lang-kotlin:4.2.3")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:4.2.3")

    // https://mvnrepository.com/artifact/com.pi4j/pi4j-core
    implementation("com.pi4j:pi4j-core:2.1.1")
    implementation("com.pi4j:pi4j-plugin-raspberrypi:2.1.1")
    implementation("com.pi4j:pi4j-plugin-pigpio:2.1.1")
    implementation("com.pi4j:pi4j-plugin-linuxfs:2.1.1")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

project.copy {
    val deps = File(project.buildDir, "dependencies")
    deps.mkdirs()
    from(project.configurations["runtimeClasspath"])
    into(deps)
}

application {
    mainClass.set("MainKt")
}