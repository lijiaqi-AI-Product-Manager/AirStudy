plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.feature.booking"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))

    implementation(project(":core:core-ui"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
