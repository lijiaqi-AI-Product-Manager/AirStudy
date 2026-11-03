plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.feature.stats"
}

dependencies {
    implementation(project(":service-focus"))
    implementation(project(":feature:feature-scene"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
