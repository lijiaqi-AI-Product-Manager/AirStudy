plugins {
    id("projectfocus.android.library")
    id("projectfocus.android.room")
    id("projectfocus.android.hilt")
}

android {
    namespace = "com.projectfocus.core.database"

    // Room 的 schema 导出目录由 projectfocus.android.room 插件设成
    // ${projectDir}/schemas —— 那是**提交进 git 的资产**，
    // 每个版本的 schema JSON 都要留档，否则无法做 MigrationTestHelper 回归。

    testOptions {
        unitTests {

            //   android.jar 是 stub，方法体全抛 "not mocked"。
            //   includeAndroidResources + Robolectric 才能拿到真实实现。
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }
}

dependencies {
    // api：所有依赖数据库的模块都要能看见领域模型与协程
    api(project(":core:core-model"))
    api(project(":core:core-common"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.hilt.android)
    implementation(libs.timber)


    //   1. 转换器用 org.json，JVM stub 跑不了
    //   2. DAO 测试要 SQLite，Robolectric 自带内存 SQLite 实现
    //   3. 沙箱没有 /dev/kvm，启动不了模拟器，instrumentation 测试全部不可用
    //   → Robolectric 是这里唯一能真实执行 SQL 的路径。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    //   **不能用 org.json.JSONObject**：Android 单元测试跑的是 android.jar 的
    //   stub 版本，所有方法体都是 `throw new RuntimeException("not mocked")`。
    //   （除非开 unitTests.returnDefaultValues，但那会把所有未 mock 的调用
    //   静默返回 null/0，是更糟的选择。）
    testImplementation(libs.kotlinx.serialization.json)
}
