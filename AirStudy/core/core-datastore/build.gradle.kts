plugins {
    id("projectfocus.android.library")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.core.datastore"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(libs.androidx.datastore.preferences)

    //
    //   下游模块（feature-profile 的测试）要自己构造一个真实的
    //   `DataStore<Preferences>`（`PreferenceDataStoreFactory.create`），
    //   而那个工厂类的类型签名里出现 `DataStore`。
    //   用 `implementation` 时，下游编译报的是：
    //
    //     Unresolved reference 'PreferenceDataStoreFactory'
    //     Cannot access class 'androidx.datastore.core.DataStore'.
    //     Check your module classpath for missing or conflicting dependencies.
    //
    //   第二句才是真相 —— 第一句完全把人往"包名写错了"的方向引。
    //

    //     `SettingsRepository` 的构造函数参数是 `DataStore<Preferences>`，
    //     所以它就是公开 API 的一部分。测试要构造它 = 正当理由。
    //     （这与"为了方便把一切都 api"是两件事：那个是懒，这个是契约。）
    api(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.timber)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
}
