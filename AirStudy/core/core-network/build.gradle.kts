plugins {
    id("projectfocus.android.library")
    id("projectfocus.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}


//
//   16 个 DTO 与 SseEvent 的**序列化形状**必须客户端与服务端共用一份定义。
//   服务端自己再抄一遍 DTO，`@SerialName` 就会漂移 ——
//   "private" 变 "isPrivate" 这类错只在联调时暴露。
//
//   做法：把**不碰 Android API** 的源码（dto/、sse/SseEvent.kt）放进
//   `src/jvmMain/kotlin`，额外注册一个纯 JVM 的 sourceSet 并编成 jar。
//   `:server` 用 `configuration: jvmApi` 依赖这个 jar；`:app` 走 debug 变体。
//   同一份源码，两个消费者，零拷贝。
android {
    namespace = "com.projectfocus.core.network"
}


//

//   03:44 那次 compileDebugKotlin 之所以能过，是因为当时这里写的是
//       android.sourceSets.getByName("main").kotlin.srcDir("src/jvmMain/kotlin")
//   —— 生效过，但那一行后来被删掉（注释还留着描述它，代码没了），
//   于是 FocusApi.kt 的 `import com.projectfocus.core.network.dto.*` 直接 Unresolved。
//   `build/libs/core-network-jvm.jar` 只有 261 字节（空 jar）也印证了同一个问题：
//   注释里说的「jvmApi 配置」从来没真正存在过。
//
//   现在的做法：显式把该目录并进 main 源集。
//   Android 库没有 `jvmMain` 这个内置 sourceSet —— 目录叫 jvmMain 只是
//   「这里不放任何 android.* import」的**约定名**，实际归属由下面这行决定。
//   :server 反向挂同一个目录（见 server/build.gradle.kts 的 checkContractPurity），
//   两边编译同一份物理源码，契约零漂移。
android.sourceSets.getByName("main").kotlin.srcDir("src/jvmMain/kotlin")

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.timber)
    // 其余测试依赖（junit4 / truth / mockk / turbine / coroutines-test /
    // arch-core-testing）由 AndroidLibraryConventionPlugin 统一加，这里不重复写。

    testImplementation(project(":core:core-database")) // 只为 Robolectric 运行时（org.json）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)


    //   证明不了"请求真的发到了正确的 path、body 真的是这个 JSON"。
    testImplementation(libs.okhttp.mockwebserver)
}


//   适配器，它依赖 `kotlinx-serialization-json` 的 `Json.asConverter` 扩展
//   （在 `retrofit2.converter.kotlinx.serialization` 包里）。
//   这里显式声明一份 `kotlinx-coroutines-core`，因为 `SseClient` 用了
//   `ensureActive()` / `supervisorScope`，只在 android 变体里由
//   coroutines-android 传递过来，测试变体不一定拿得到。
dependencies {
    api(libs.kotlinx.coroutines.core)
}


//   debug 走本机（10.0.2.2 = Android 模拟器指向宿主的固定地址），
//   release 走正式域名。发版时不需要改任何 Kotlin 代码。
android {
    defaultConfig {
        buildConfigField("String", "API_BASE_URL", "\"https://api.projectfocus.app/\"")
    }
    buildTypes {
        debug {
            // 10.0.2.2 是 AVD 里指向宿主机的别名；真机联调改成局域网 IP
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.projectfocus.app/\"")
        }
    }
}


//   `internal`（`Factory`、`Serializer` 都是；`javap` 看到 public 是因为
//   Kotlin 的 internal 在字节码层面就是 public + 名字修饰）。
//   → 官方用法只有 `json.asConverterFactory(mediaType)` 这一个扩展函数，
//     而它**不存在于 2.11.0**（该扩展是 Retrofit 2.12/3.x 引入的）。
//

//   `restapi` 的包名与 `Response` 的处理方式，M1 不动）。
//   所以：**自己写一个 Converter.Factory**，只依赖 kotlinx.serialization
//   的公开 API（`serializer(Type)` / `decodeFromString`）。
//   代价是 60 行；收益是不再被 upstream 的 internal 可见性卡住。
// 服务端复用 DTO 的方式见 docs/contract-sharing.md：
// 服务端**不**直接依赖本模块，改为在 :server 里用 `kapt` 之外的方式
// —— 其实最简单的是把 dto/sse 源码目录直接加进 :server 的 srcDirs。
