import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.projectfocus.buildlogic.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * app 模块专用。
 *
 * 所有公共配置集中在这里，模块内 build.gradle.kts 只保留「本模块特有」的东西。
 *
 * ⚠️ AGP 9 起 `CommonExtension` 不再是泛型接口（`CommonExtension` 无类型参数），
 *    因此 Application 与 Library 各写一份配置块，公共部分抽成两个私有扩展函数。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        // AGP 9 起内置 Kotlin 支持，不再需要（也不能）apply kotlin-android 插件

        extensions.configure<ApplicationExtension> {
            compileSdk = 37
            defaultConfig {
                minSdk = 26
                targetSdk = 37
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

                resourceConfigurations += SOURCE_RESOURCE_CONFIGURATIONS
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
                // app 是最终打包方，desugaring 必须开
                isCoreLibraryDesugaringEnabled = true
            }
            applySharedAndroidConfig()
        }

        // desugaring 运行时库必须加在 app 上
        with(dependencies) {
            add("coreLibraryDesugaring", libs.findLibrary("desugar-jdk-libs").get())


            //   之前 app 模块没有测试源码，所以这里一直没加 —— 加第一份测试
            //   （WidgetMirrorMappingTest）时才发现「Unresolved reference: truth」。
            //   教训：约定插件里漏一组配置，不会在构建时报警，只会等到
            //   「第一次有人写这种测试」时才暴露，而那时人已经在怀疑自己的 import 了。
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("turbine").get())
        }
    }
}

/**
 * library 模块的公共配置，供 AndroidLibraryConventionPlugin 调用。
 *
 * 注意：library **不开** desugaring —— 开了但没配 coreLibraryDesugaring 依赖会直接构建失败
 * （"coreLibraryDesugaring configuration contains no dependencies"）。
 */
internal fun LibraryExtension.applyLibraryDefaults() {
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += SOURCE_RESOURCE_CONFIGURATIONS
        consumerProguardFiles(CONSUMER_RULES_FILE)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles(CONSUMER_RULES_FILE)
        }
        debug {
            isMinifyEnabled = false
        }
    }
    applySharedAndroidConfig()
}


internal const val CONSUMER_RULES_FILE = "consumer-rules.pro"

// default资源始终保留；与原认证/计划/场景资源的七个配置一致，App与Library共用。
private val SOURCE_RESOURCE_CONFIGURATIONS = listOf("en", "zh", "zh-rTW", "ja", "ko", "ru")



















































internal fun Project.ensureConsumerRulesFile() {
    val file = layout.projectDirectory.file(CONSUMER_RULES_FILE).asFile
    if (file.exists()) return
    file.writeText(
        """
        # ${name} 的 consume 侧 R8 规则（给**使用本模块的工程**生效，不是给本模块自己）。
        #
        # ★ 本文件由 buildSrc 的 projectfocus.android.library 约定插件自动生成，
        #   无需手工创建；如需增加规则，直接在本文件里追加即可
        #   （插件只在文件不存在时写入，不会覆盖你的改动）。
        #
        # ★ 为什么必须有这个文件：AGP 在 mergeConsumerProguardFiles 阶段
        #   读它，文件不存在会直接让 lint / assembleRelease 失败。
        #
        # ★ 现在为空是有意的：本模块没有需要向消费方暴露的、会被 R8 裁掉的东西。
        #   真正的规则只在确实需要的地方写（例：core-database 的 Room 规则）。
        """.trimIndent() + "\n",
    )
}

/** Application 与 Library 的公共设置（两个 DSL 都支持的那部分 API） */
private fun ApplicationExtension.applySharedAndroidConfig() {
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true

            //   isReturnDefaultValues = true 会让所有 android.jar 的调用
            //   **静默返回 0 / null 而不抛异常** —— 测试会"绿"，但测的是空气。
            //   例如 org.json.JSONArray("[]") 会返回 null、Room 的
            //   SupportSQLiteDatabase 调用全部变 no-op，迁移测试永远"通过"。
            //   这是最危险的一类假绿，宁可让测试因为 "not mocked" 报错，
            //   再去用 Robolectric 补真实实现。
            isReturnDefaultValues = false
        }
    }
    packaging {
        resources {
            excludes += PACKAGING_EXCLUDES
        }
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        // 硬编码文案在业务迭代期噪音过大，先降级；硬编码颜色由 CI 自定义检查兜住
        disable += SHARED_LINT_DISABLES
    }
}

private fun LibraryExtension.applySharedAndroidConfig() {
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true

            //   isReturnDefaultValues = true 会让所有 android.jar 的调用
            //   **静默返回 0 / null 而不抛异常** —— 测试会"绿"，但测的是空气。
            //   例如 org.json.JSONArray("[]") 会返回 null、Room 的
            //   SupportSQLiteDatabase 调用全部变 no-op，迁移测试永远"通过"。
            //   这是最危险的一类假绿，宁可让测试因为 "not mocked" 报错，
            //   再去用 Robolectric 补真实实现。
            isReturnDefaultValues = false
        }
    }
    packaging {
        resources {
            excludes += PACKAGING_EXCLUDES
        }
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        disable += SHARED_LINT_DISABLES
    }
}


private val SHARED_LINT_DISABLES = setOf(
    "HardcodedText",
    "GradleDependency",
    "AndroidGradlePluginVersion",
    "UnusedResources",
    "QueryPermissionsNeeded",
    "BatteryLife",
)

private val PACKAGING_EXCLUDES = setOf(
    "/META-INF/{AL2.0,LGPL2.1}",
    "/META-INF/DEPENDENCIES",
    "/META-INF/LICENSE*",
    "/META-INF/NOTICE*",
    "DebugProbesKt.bin",
    "kotlin-tooling-metadata.json",
)
