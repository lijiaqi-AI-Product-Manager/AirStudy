plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.feature.completion"
}

dependencies {
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))
    // R8：落地页的“完成飞行”必须调用 SessionRepository.settle()。
    // 依赖方向 app → feature → service → core，不构成环。
    implementation(project(":service-focus"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
