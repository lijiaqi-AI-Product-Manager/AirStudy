plugins {
    id("projectfocus.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    withSourcesJar()
}

dependencies {
    // 领域模型需要调用 FlightMath 做 distanceKm 口径校验（recomputedDistanceKm）
    api(project(":core:core-common"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    testImplementation(libs.junit4)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.truth)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
