plugins {
    id("projectfocus.android.library")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.core.analytics"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(libs.hilt.android)
    implementation(libs.timber)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
