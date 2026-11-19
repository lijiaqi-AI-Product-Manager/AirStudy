plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.feature.focus"
}

dependencies {
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))
    implementation(project(":service-focus"))
    implementation(libs.androidx.viewpager2)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
