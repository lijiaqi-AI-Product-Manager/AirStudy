import com.projectfocus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * 纯 Kotlin JVM 模块（core-common / core-model 等不碰 Android 的模块）。
 * 这类模块可被 server（Ktor）直接复用，是「客户端与服务端共享 DTO」的前提。
 *
 * 注意：Kotlin 侧 jvmTarget 与 Java 侧 toolchain 必须一致（都 17），
 * 否则 Gradle 的 JVM-target 校验会直接 fail。
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")



        // 'compileJava' (20) 与 'compileKotlin' (17) 不一致而直接失败：
        //   Inconsistent JVM-target compatibility detected
        // 用 toolchain 统一到 17，保证本地产物与 CI（JDK 17）一致。
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }

        tasks.withType(KotlinJvmCompile::class.java).configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.addAll(
                    "-jvm-default=enable",
                    "-opt-in=kotlin.RequiresOptIn",
                )
            }
        }
    }
}
