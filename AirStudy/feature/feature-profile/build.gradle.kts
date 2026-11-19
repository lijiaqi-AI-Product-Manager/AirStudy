plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")
    id("projectfocus.android.hilt.test")
}

android {
    namespace = "com.projectfocus.feature.profile"
    buildFeatures {

        //   设置行是代码创建的，不需要 binding ——
        //   它们的内容来自 PrefSpecs，写进 XML 就是抄第二遍（见 SettingsFragment 注释）。
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))

    //   设置页是最"吃"设计系统的页面：40 行 × 4 种控件，全部由 token 驱动。
    implementation(project(":core:core-ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
