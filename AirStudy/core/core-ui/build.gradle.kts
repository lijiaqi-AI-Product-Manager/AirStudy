plugins {
    id("projectfocus.android.library")
}

android {
    namespace = "com.projectfocus.core.ui"
}

dependencies {
    // Actual original XML uses FlexboxLayout; official 3.0.0 is this implementation dependency, not an original-version claim.
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.navigation.fragment)
    implementation(libs.timber)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)

    //   纯 JVM 的 android.jar 只有空壳（会抛 "not mocked"）。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
