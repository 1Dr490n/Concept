plugins {
    kotlin("jvm") version "1.8.0"
}

group = "de.drgn"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(files("C:\\Users\\armin\\programming\\libs\\IRBuilder\\out\\artifacts\\IRBuilder\\IRBuilder.jar"))

}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}