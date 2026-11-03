import java.util.Properties

plugins {
    id("projectfocus.android.application")
    id("projectfocus.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}


//   签名材料一律不入库：优先读工程根目录的 keystore.properties（已被 .gitignore 忽略，
//   模板见 keystore.properties.example），其次读环境变量（给 CI 用）。
//   都没有时不报错、照常出 unsigned 包，并在配置阶段打一条警告 ——
//   新机器 / CI 没证书也能编译，但不会把 unsigned 包误当成可安装的正式包。
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingValue("storeFile", "PF_RELEASE_STORE_FILE")

val mapProperties = Properties().apply {
    val file = rootProject.file("map.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun mapConfigurationValue(key: String, environment: String): String =
    (mapProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(environment).orNull.orEmpty()).trim()

fun mapJavaString(value: String): String {
    require(value.all { it.code in 32..126 }) { "Map configuration contains unsupported characters" }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.projectfocus.app"

    defaultConfig {
        applicationId = "com.projectfocus.app"
        versionCode = 1
        versionName = "0.1.0-m1"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "HMS_MAP_API_KEY", mapJavaString(mapConfigurationValue("apiKey", "PF_HMS_MAP_API_KEY")))
        buildConfigField("String", "HMS_MAP_APP_ID", mapJavaString(mapConfigurationValue("appId", "PF_HMS_MAP_APP_ID")))
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "PF_RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PF_RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PF_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {

            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

if (releaseStoreFile == null) {
    logger.warn(
        "⚠ release 未配置签名：将产出 app-release-unsigned.apk（不可直接安装）。" +
            "在 project-focus/keystore.properties 或 PF_RELEASE_* 环境变量里提供证书即可。",
    )
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-analytics"))

    implementation(project(":feature:feature-onboarding"))
    implementation(project(":feature:feature-home"))
    implementation(project(":feature:feature-airport"))
    implementation(project(":feature:feature-booking"))
    implementation(project(":feature:feature-flight"))
    implementation(project(":feature:feature-focus"))
    implementation(project(":feature:feature-completion"))
    implementation(project(":feature:feature-record"))
    implementation(project(":feature:feature-stats"))
    implementation(project(":feature:feature-achievement"))
    implementation(project(":feature:feature-scene"))
    implementation(project(":feature:feature-sound"))
    implementation(project(":feature:feature-group"))
    implementation(project(":feature:feature-profile"))

    implementation(projects.widget)
    implementation(projects.serviceFocus)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.activity.ktx)

    //   不依赖 activity-ktx 的传递依赖 —— 传递依赖可能随版本变化而消失。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.splashscreen)
    implementation(libs.hilt.navigation.fragment)
    implementation(libs.timber)



    //     没有 Robolectric —— 因为**大部分** app 层测试是纯 JVM 的
    //     （如 WidgetMirrorMappingTest），没必要为此在所有模块里挂 Robolectric 的
    //     60MB 依赖。有渲染需求时在这里显式声明。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.androidx.fragment.testing)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.retrofit)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}

// One registration integration test drives the actual packaged JVM backend
// through the actual Android Activity/OkHttp client and an owned local mail peer.
// The backend remains a separate process/dependency graph, not an app runtime dependency.
tasks.withType<Test>().configureEach {
    dependsOn(":server:installDist")
    systemProperty("pf.registration.server.libDir",
        rootProject.layout.projectDirectory.dir("server/build/install/server/lib").asFile.absolutePath)
}
