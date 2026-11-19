plugins {
    id("projectfocus.android.library")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.service.focus"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-datastore"))
    implementation(project(":core:core-network"))
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)

    //   setShowWhenLocked、以及 OnBackPressedDispatcher 的行为一致。
    //   用 ComponentActivity 也能跑，但要自己处理主题继承，不值当。
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    //   core-datastore 在 implementation 配置下**不会**把 Preferences / DataStore
    //   的类透传给本模块，所以这两个必须显式声明。
    //   用 implementation 而不是 api：本模块不对外暴露 Preferences 类型，
    //   只有 SessionRepository 内部用（WidgetMirror 才是对外的）。
    implementation(libs.androidx.datastore.preferences)

    //   它属于 datastore（非 preferences）jar。只在测试配置里要。
    testImplementation("androidx.datastore:datastore-core:${libs.versions.datastore.get()}")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}
