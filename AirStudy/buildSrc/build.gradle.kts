plugins {
    `kotlin-dsl`
}

group = "com.projectfocus.buildlogic"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // buildSrc 里只能通过 plugins {} 声明 AGP/KSP，见 buildSrc/settings.gradle.kts
    implementation(libs.buildlogic.agp)
    implementation(libs.buildlogic.kotlin)
    implementation(libs.buildlogic.ksp)
    // Kotlin 序列化编译器插件（根项目 apply false 时也能解析）
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.4.21")
    // Hilt Gradle 插件
    implementation("com.google.dagger:hilt-android-gradle-plugin:2.60.1")

    // buildSrc 自身的测试（门禁任务的正向/反向用例）
    //

    //   门禁的最大风险不是「误报」（误报会立刻被发现并修掉），而是**假阴性**：
    //   扫描器有 bug → 报告 0 违规 → 所有人都以为代码干净 → 技术债静默堆积。
    //   假阴性没有任何外部信号能发现它。所以必须有一组「故意写脏数据、断言必须报警」的用例。
    //

    //   独立的 gradleApi() 依赖，测试里能直接 new 出 Task 实例来跑 TaskAction。
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
}

tasks.named<Test>("test") {

    //   要用 ProjectBuilder 建一个临时的 project 才能 inject 它的 @OutputFile Property。
    systemProperty("org.gradle.testkit.runner.gradle-version", gradle.gradleVersion)
}

tasks.named("test") { dependsOn(generateAccessor) }

// 生成 Project.libs 扩展：convention plugin 内部通过 project.libs 访问版本目录
val accessorDir: Provider<Directory> = layout.buildDirectory.dir("generated/accessor")
val generateAccessor by tasks.registering {
    val dir = accessorDir
    outputs.dir(dir)
    doLast {
        val file = dir.get().file("VersionCatalogAccessor.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.projectfocus.buildlogic
            |
            |import org.gradle.api.Project
            |import org.gradle.api.artifacts.VersionCatalog
            |import org.gradle.api.artifacts.VersionCatalogsExtension
            |import org.gradle.kotlin.dsl.getByType
            |
            |internal val Project.libs: VersionCatalog
            |    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
            |
            """.trimMargin()
        )
    }
}

kotlin.sourceSets.getByName("main").kotlin.srcDir(accessorDir)
tasks.named("compileKotlin") { dependsOn(generateAccessor) }
