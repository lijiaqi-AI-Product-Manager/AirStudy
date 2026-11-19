import com.android.build.api.dsl.LibraryExtension
import com.projectfocus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType


class AndroidHiltTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        extensions.configure<LibraryExtension> {
            testOptions {
                unitTests {

                    //   findViewById / getString 一律抛 Resources$NotFoundException。
                    //   （实测：加上之前连布局都 inflate 不出来）
                    isIncludeAndroidResources = true
                }
            }
        }

        with(dependencies) {
            add("testImplementation", libs.findLibrary("hilt-android-testing").get())

            //   `@HiltAndroidTest` 会让 Hilt 生成 `Hilt_TestXxx` 之类的中间类，
            //   少了 kspTest 编译期就报 Unresolved reference。
            add("kspTest", libs.findLibrary("hilt-compiler").get())

            //   放这里而不是各模块自己写，是因为凡是能测 Hilt Fragment 的模块
            //   基本都要这一套，重复声明只会让版本漂移。
            add("testImplementation", libs.findLibrary("androidx-fragment-testing").get())
        }

        tasks.withType<Test>().configureEach {

            //   否则它加载不到 dagger.hilt.android.testing.HiltTestApplication。
            //   （@Config(application = ...) 里的 Class 引用在测试编译期就绑定，
            //    运行期由 Robolectric 的 sandbox classloader 解析 —— 这个 classloader
            //    默认会继承测试进程的 classpath，所以这里不需要额外配置。）
            //

            //   这里会立刻以可读的方式失败，而不是让 11 个渲染测试报 11 次
            //   "GeneratedComponent" 天书。
            doFirst {
                val probe = "dagger.hilt.android.testing.HiltTestApplication"
                if (classpath?.asPath?.let { java.io.File(it).exists() } != false) {
                    // 用 classpath 反射探测，避免误把"文件不存在"当成"类不存在"
                    val found = try {
                        Class.forName(probe, false, javaClass.classLoader)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                    if (!found) {
                        logger.warn(
                            "[hilt-test] 警告：$probe 不在测试 classpath。" +
                                "Hilt Fragment 渲染测试将全部失败，" +
                                "请确认模块 apply 了 projectfocus.android.hilt.test。",
                        )
                    }
                }
            }
        }
    }
}
