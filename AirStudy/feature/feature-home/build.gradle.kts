plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.feature.home"
}

dependencies {

    //   首页要把「会话状态」投影成「UI 状态」（见 HomeUiState 的类注释）。
    //   依赖方向是 feature → service，与服务(service-focus)→core 的方向一致，
    //   **没有回指**，不构成循环。
    implementation(project(":service-focus"))

    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))


    implementation(project(":feature:feature-sound"))

    implementation(libs.huawei.maps)
    // The facade alone has no embedded distance/map provider on non-HMS hosts.
    implementation(libs.huawei.maps.basic)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
