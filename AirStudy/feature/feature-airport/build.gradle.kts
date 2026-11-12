plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")

    //   Hilt 的字节码改写会生成 `Hilt_AirportHostActivity` 基类 ——
    //   少了 kspTest 就没有这个类，报
    //   `NoClassDefFoundError: Hilt_AirportHostActivity`（在 JUnit 扫描阶段，
    //   连测试方法都没进去，报错里完全没有业务信息）。
    id("projectfocus.android.hilt.test")
}

android {
    namespace = "com.projectfocus.feature.airport"
}

dependencies {
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))

    //   （buildSrc/AndroidFeatureConventionPlugin.kt:25），这里不再重复声明。

    testImplementation(libs.robolectric)

    //   为什么不用 mock：见 core-datastore 的 SettingsRepositoryTest 头注释 ——

    //   distinctUntilChanged、旧键迁移这三件"线上才炸"的事。

    //     `implementation`（不传递），所以这里必须自己声一次。
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.androidx.test.core)

    //   `projectfocus.android.hilt.test` 插件统一提供，此处不重复声明。
}
