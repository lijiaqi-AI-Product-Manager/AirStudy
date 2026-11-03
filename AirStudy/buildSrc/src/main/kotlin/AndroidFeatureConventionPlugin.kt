import com.projectfocus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.project

/**
 * feature 模块约定：在 android-library 基础上，
 * 强制依赖 core-ui，并提供 MVVM 三件套（lifecycle-viewmodel / navigation）。
 *
 * 设计意图：让「一个 feature 模块 = 一个可独立开发、独立测试、独立截图回归的功能域」。
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("projectfocus.android.library")

        with(dependencies) {
            add("implementation", project(":core:core-common"))
            add("implementation", project(":core:core-model"))
            add("implementation", project(":core:core-ui"))

            add("implementation", libs.findLibrary("androidx-core-ktx").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-ktx").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-savedstate").get())
            add("implementation", libs.findLibrary("androidx-navigation-fragment").get())
            add("implementation", libs.findLibrary("androidx-fragment-ktx").get())
            add("implementation", libs.findLibrary("androidx-recyclerview").get())
            add("implementation", libs.findLibrary("androidx-constraintlayout").get())
            add("implementation", libs.findLibrary("google-material").get())
            add("implementation", libs.findLibrary("timber").get())
        }
    }
}
