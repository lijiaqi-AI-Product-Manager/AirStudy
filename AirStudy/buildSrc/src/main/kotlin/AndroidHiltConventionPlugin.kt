import com.projectfocus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Hilt 约定。单独成插件而不是塞进 feature/library，
 * 是因为纯 View 层模块（如 core-ui）不应被迫引入 DI 框架。
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        // 必须先 apply 官方 Hilt Gradle 插件，否则 KSP 侧会报

        pluginManager.apply("com.google.dagger.hilt.android")
        pluginManager.apply("com.google.devtools.ksp")

        with(dependencies) {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
            add("implementation", libs.findLibrary("javax-inject").get())
        }
    }
}
