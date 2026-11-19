import com.projectfocus.buildlogic.CheckNoHardcodedColorsTask

// buildSrc 里已把 AGP / Kotlin / KSP 加入构建 classpath，
// 因此根项目这里不能再带 version（否则报 "already on the classpath with an unknown version"）。
plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("com.google.devtools.ksp") apply false
    id("com.google.dagger.hilt.android") apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

// ======================================================================
// CI 门禁：硬编码颜色扫描
// ======================================================================
//

//   1. core/core-ui 是 token 的**定义处**，它必须能写十六进制 —— 否则没人能写


//      这些模块由 code review 兜住，门禁只管 UI 层
//   3. server/ 是 Ktor 后端，与颜色无关
//

//   按文件名放行 colors.xml 会把 feature 模块里的 colors.xml 一起放过 ——

tasks.register<CheckNoHardcodedColorsTask>("checkNoHardcodedColors") {
    group = "verification"
    description = "扫描业务模块里的硬编码颜色（#RRGGBB / Color.parseColor / 0xAARRGGBB）"

    sources.from(
        fileTree(rootDir) {
            include("app/src/**/*.kt", "app/src/**/*.java", "app/src/**/*.xml")
            include("feature/*/src/**/*.kt", "feature/*/src/**/*.java", "feature/*/src/**/*.xml")
            include("widget/src/**/*.kt", "widget/src/**/*.java", "widget/src/**/*.xml")
            include("service-focus/src/**/*.kt", "service-focus/src/**/*.java", "service-focus/src/**/*.xml")
            exclude("**/build/**")
            // 测试中的像素、对比度和负例色值不属于发布代码。
            exclude("**/src/test/**", "**/src/androidTest/**", "**/src/testFixtures/**")
        },
    )
    allowedPathPrefixes.set(
        setOf(
            // token 的唯一定义处
            "core/core-ui/src/main/res/values",
            "core/core-ui/src/main/res/values-night",
            // 启动图标前景层必须是具体色值（adaptive icon 不支持 @color 引用）
            "app/src/main/res/drawable",
            "app/src/main/res/drawable-anydpi-v26",
        ),
    )
    rootDirPath.set(rootDir.absolutePath)
    report.set(layout.buildDirectory.file("reports/no-hardcoded-colors.md"))


    //
    //   ① widget/.../WidgetPalette.kt
    //      Glance 的 Color 是值对象不是资源引用，必须硬编码。
    //      语义正确性由 WidgetPaletteTest 逐条断言等于 core-ui token。
    //
    //   ② service-focus/.../values/colors.xml
    //      service-focus **不依赖 core-ui**（否则玻璃渲染/字体/十几套控件样式
    //      全进依赖图，只为 5 个色值）。色值定义本身必须写在这里。
    //      语义正确性由 DimensParityTest 断言与 core-ui 一致。

    //

    //     那会让 service-focus/values/ 下新增的 the mes.xml 里也能写色值。
    //     精确到文件才能表达「就这一个文件、就这几行」。
    allowedExactFiles.set(
        setOf(
            "widget/src/main/kotlin/com/projectfocus/widget/glance/WidgetPalette.kt",
            "service-focus/src/main/res/values/colors.xml",
            "service-focus/src/main/res/values-night/colors.xml",
            "widget/src/main/res/values/colors.xml",
        ),
    )
}

// ======================================================================

// ======================================================================
//



//

tasks.register<com.projectfocus.buildlogic.CheckApkSizeTask>("checkApkSize") {
    group = "verification"
    description = "检查 release APK 体积是否超预算（60 MiB）"


    // 兼容已签名和 unsigned 文件名，不在任务图解析时读取尚未生成的文件。
    apkDirectory.set(layout.projectDirectory.dir("app/build/outputs/apk/release"))
    budgetBytes.set(62_914_560L)
    report.set(layout.buildDirectory.file("reports/apk-size.md"))


    //   避免根项目直接引用 :app 的 configuration（那会让根项目带上 AGP 的变体解析）。
    dependsOn(":app:assembleRelease")
}
