plugins {
    id("projectfocus.android.feature")
    id("projectfocus.android.hilt")

    //   Hilt 的字节码改写会生成 `Hilt_RecordHostActivity` 基类 ——
    //   少了 kspTest 就没有这个类，报
    //   `NoClassDefFoundError: Hilt_RecordHostActivity`（在 JUnit 扫描阶段，
    //   连测试方法都没进去，报错里完全没有业务信息）。
    id("projectfocus.android.hilt.test")
}

android {
    namespace = "com.projectfocus.feature.record"
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-datastore"))

    //   （buildSrc/AndroidFeatureConventionPlugin.kt:25），这里不再重复声明。

    //
    //   只为了一个东西：`TodayStatsNotifier`（一个零依赖的 @Singleton，
    //   只有一个 `notifyChanged()`）。删除记录后要通知桌面组件刷新今日汇总。
    //

    //     feature 模块依赖 service 模块，会让 feature-record 的依赖面变大。
    //     所以本模块**只允许**用 TodayStatsNotifier 这一个类型，
    //     不允许 import SessionRepository / SessionController / FocusEnforcer。
    //

    //     · 把 Notifier 下沉到 core-common —— 更干净，但 core-common 里
    //       现在全是纯工具类，塞一个 @Singleton + SharedFlow 进去会打破它的定位；
    //     · 用 LocalBroadcastManager / 全局 EventBus —— 隐式契约，无法静态检查，
    //       而且 BroadcastReceiver 在主线程，刷新要再切线程；
    //     · **不通知** —— 桌面上的连续天数会一直是错的，直到用户再飞一趟。
    //   → 选直接依赖。service-focus 已经是"应用服务层"，feature 依赖它是
    //     正常方向（app → feature → service → core），不是反向。
    implementation(project(":service-focus"))

    //
    //   只为了分享卡片：`BoardingPassView`（自绘登机牌）与 `BoardingPassData`
    //   都在 feature-completion 里。分享"这趟飞行的登机牌"本质上就是
    //   把到站页那张图导出来 —— 复用同一个 View 才能保证
    //   「到站页看到的」和「分享出去的」是同一张。
    //

    //     所以不存在循环。未来如果 completion 也要调 record 的东西，
    //     必须通过 core-model 或者宿主壳中转，不能直接加反向依赖。
    //

    //     两张图一旦分家，就会有一天出现"到站页的章是 COMPLETED、
    //     分享图上是空白"这类不一致，而且没有任何测试能发现。
    implementation(project(":feature:feature-completion"))
    implementation(project(":feature:feature-scene"))
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation(libs.timber)


    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.hilt.android.testing)


    //   Robolectric 默认把 Log 吞掉，`Thread.sleep` 这种"看不见的等待"
    //   出问题时就完全没有线索。
    testImplementation(libs.timber)
}
