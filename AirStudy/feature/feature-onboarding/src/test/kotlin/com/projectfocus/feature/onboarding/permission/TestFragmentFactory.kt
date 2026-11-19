package com.projectfocus.feature.onboarding.permission


























































object TestFragmentFactory {










    @Volatile
    private var current: PermissionPlatform? = null

    fun stage(platform: PermissionPlatform) {
        current = platform
    }

    fun unstage() {
        current = null
    }

    /** 供 [TestableFragment] 读取 */
    fun take(): PermissionPlatform =
        requireNotNull(current) {
            "TestFragmentFactory.stage() 没被调用就构造了 TestableFragment —— " +
                "请用 HiltTestHostActivity.kt 里的 launchHiltFragment() 启动测试场景"
        }
}
