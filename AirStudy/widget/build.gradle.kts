plugins {
    id("projectfocus.android.library")
    id("projectfocus.android.hilt")

    //   `provideContent { ... }` 里的 lambda 不会被编译器重写，
    //   编译期就会报 "Composable invocations can only happen from
    //   the context of a @Composable function"。
    //   版本与 kotlin 绑定（同一个 version.ref），因为 Kotlin 2.0 起
    //   Compose 编译器已并入 Kotlin 仓库，版本错配会直接编译失败。
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.projectfocus.widget"

    buildFeatures {

        //   Glance 自带一套 RemoteViews 版的 Compose 子集（androidx.glance.*），
        //   不需要 androidx.compose.ui。这样 widget 模块带来的体积增量只有
        //   glance 本身（约 400KB），而不是整套 Compose UI（约 4MB）。
        compose = true
    }
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-datastore"))
    implementation(project(":core:core-ui"))
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.timber)

    //   不需要真机 / Robolectric。这是 Glance 相对传统 RemoteViews
    //   最大的工程优势 —— 传统 AppWidget 的布局几乎无法测。
    testImplementation(libs.glance.appwidget.testing)
    testImplementation(libs.glance.testing)
}
