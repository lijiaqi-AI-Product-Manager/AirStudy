plugins {
    id("projectfocus.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}












application {
    mainClass.set("com.projectfocus.server.ApplicationKt")
}

// ═══════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════
//
// 为什么不是 `implementation(project(":core:core-network"))`：
//   :core:core-network 是 **Android library**（有 AndroidManifest、有 R、
//   BuildConfig 指向 android.jar）。JVM 服务端依赖它，编出来的
//   classes 里带着 android.* 引用，一启动就 NoClassDefFoundError。
//
// 为什么不是"服务端自己抄一份 DTO"：
//   `@SerialName("private")` 这种细节只要抄错一个字符，
//   就是联调时排查半天的问题。**契约必须物理上只有一份源码。**
//
// 做法：把 core-network 里**只依赖 kotlinx.serialization** 的两个目录
//   （dto/、sse/SseEvent.kt）作为额外的 srcDir 挂到服务端的 main 源集。
//   编译时它们是服务端自己的代码，运行时不依赖任何客户端模块。
//   ⚠️ 约束：这两个目录里**不准出现任何 android.* import**。
//      这条约束由 `checkContractPurity` 任务在 CI 里强制（见文末）。
sourceSets {
    named("main") {
        kotlin.srcDir("../core/core-network/src/jvmMain/kotlin")
    }
}


//   不加这一条，某天有人在 DTO 里 `import android.os.Parcelable`，
//   服务端会在**运行时**炸，而不是构建时。
tasks.register("checkContractPurity") {
    group = "verification"
    val contractDir = layout.projectDirectory.dir("../core/core-network/src/jvmMain/kotlin")
    inputs.dir(contractDir)
    doLast {
        val offenders = contractDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { l -> l.trimStart().startsWith("import android") } }
            .map { it.relativeTo(contractDir.asFile).path }
            .toList()
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "契约源码里出现了 android.* 依赖，服务端无法使用：$offenders\n" +
                    "把这些 import 抽到一个 Android 专属的扩展文件里。",
            )
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn("checkContractPurity")
}

dependencies {
    // ── Ktor ──────────────────────────────────────────────
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)

    // ── 数据层 ────────────────────────────────────────────
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    // H2 只用于测试（内存库，免装 PG 就能跑集成测试）
    // The existing dev configuration defaults to H2; PostgreSQL remains required in production.
    runtimeOnly(libs.h2)
    testImplementation(libs.h2)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // ── 共享契约 ─────────────────────────────────────────
    implementation(project(":core:core-model"))

    // ── 日志 / 工具 ───────────────────────────────────────
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.bcrypt)

    //   不是 jjwt。两套 API 不兼容，混用会让 verifier() 编译不过。
    //   统一用 java-jwt：少一个依赖，且与 Ktor 插件天然对齐。
    implementation(libs.java.jwt)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val packagedServer = tasks.named<Jar>("jar")
    dependsOn(packagedServer)
    systemProperty("airstudy.server.jarPath", packagedServer.get().archiveFile.get().asFile.absolutePath)
}

tasks.named<Copy>("processResources") {
    exclude("airports/location-catalog.csv")
}

tasks.named<Jar>("jar") {
    exclude("airports/location-catalog.csv")
}

tasks.named<JavaExec>("run") {
    // 本地跑默认用 H2（免装 PG）；连真库时用 -PusePg 或环境变量覆盖
    environment("PF_DB_URL", System.getenv("PF_DB_URL") ?: "jdbc:h2:mem:pf;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
    environment("PF_DB_USER", System.getenv("PF_DB_USER") ?: "sa")
    environment("PF_DB_PASSWORD", System.getenv("PF_DB_PASSWORD") ?: "")
    environment("PF_JWT_SECRET", System.getenv("PF_JWT_SECRET") ?: "dev-only-secret-change-me-in-production-32b")
    standardInput = System.`in`
}

distributions {
    main {
        contents {
            from("src/main/resources") { into("resources") }
        }
    }
}
