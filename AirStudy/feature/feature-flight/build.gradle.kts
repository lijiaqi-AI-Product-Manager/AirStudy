plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.feature.flight"
}

dependencies {
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
