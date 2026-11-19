import com.android.build.api.dsl.LibraryExtension
import com.projectfocus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * 所有 Android library 模块的公共配置：compileSdk / minSdk / ViewBinding / 编译选项 / 单元测试依赖。
 * 各模块 build.gradle.kts 只写 namespace 和独有依赖。
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        // AGP 9 起内置 Kotlin 支持，不再需要（也不能）apply kotlin-android 插件

        extensions.configure<LibraryExtension> {
            applyLibraryDefaults()
        }


        //   AGP 在**配置阶段**就会校验 defaultConfig / buildTypes 里声明的
        //   consumerProguardFiles 是否存在，晚一步就是
        //   "Supplied consumer proguard configuration does not exist"。
        ensureConsumerRulesFile()

        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                freeCompilerArgs.addAll(
                    "-jvm-default=enable",
                    "-opt-in=kotlin.RequiresOptIn",
                    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                )
            }
        }

        with(dependencies) {
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("mockk").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("androidx-arch-core-testing").get())
        }


        //
        //   Gradle 9 起 Test 任务默认 `failOnNoDiscoveredTests = true`：
        //   只要有 test 源集参与编译、却一个测试都没发现，就判失败。
        //   实测（2026-10-26 05:24）：:service-focus 与 :widget 目前只有
        //   AndroidManifest.xml，没有 kotlin/java 测试源码 —— 但 AGP 仍会
        //   创建 debugUnitTest 源集并交给 Test 任务，于是
        //     "There are test sources present and no filters are applied,
        //      but the test task did not discover any tests to execute."
        //   整条 `gradle test` 因此红掉。
        //

        //     塞 `@Test fun placeholder() {}` 是假绿 —— 它让覆盖率报告看起来
        //     有测试，实际什么都没验。关掉检查是**诚实**的做法：
        //     没测试就是没测试，CI 上单独看覆盖率就行。
        //     一旦模块有了真测试，这条设置也不会误伤（有测试就正常跑）。
        tasks.withType<Test>().configureEach {
            failOnNoDiscoveredTests.set(false)
            applyTestJvmLimits()
        }
    }
}




























private fun Test.applyTestJvmLimits() {
    maxHeapSize = "1280m"

    //   但对本工程（1000+ 条测试）来说，forkEvery = 0（不换）会让单进程累积
    //   所有 Robolectric sandbox classloader，最终 metaspace 顶穿。
    //   设一个较大的值：既能回收，又不至于每个类都重启。
    forkEvery = 200
    // 元空间必须显式给：Robolectric 每换一个 SDK/Application 就新建 sandbox classloader，
    // 默认 metaspace 上限是 -1（无限），靠 cgroup 兜底 —— 同样会被 kill -9。
    jvmArgs("-XX:MaxMetaspaceSize=512m", "-XX:+UseSerialGC", "-Xss2m")
}
