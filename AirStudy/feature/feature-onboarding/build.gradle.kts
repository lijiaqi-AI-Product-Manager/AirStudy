plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")

    //   等 home/airport/flight 的视图层起来再逐个打开，而不是一次性全挂上 ——
    //   没有 Hilt Fragment 的模块挂上它只会拖慢构建（多一轮 KSP）。
    id("projectfocus.android.hilt.test")
}

android {
    namespace = "com.projectfocus.feature.onboarding"
}

dependencies {
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    //   `projectfocus.android.hilt.test` 插件统一提供，此处不再重复声明 ——
    //   重复声明是版本漂移的起点（一个模块升了、另一个没升，运行时 NoSuchMethodError）。
}
