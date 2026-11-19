plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.feature.scene"
}

dependencies {
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-datastore"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
