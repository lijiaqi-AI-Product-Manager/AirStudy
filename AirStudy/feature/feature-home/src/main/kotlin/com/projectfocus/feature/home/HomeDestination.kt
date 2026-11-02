package com.projectfocus.feature.home

/**
 * Home 功能域的导航目的地。
 *
 * 每个 feature 模块对外只暴露「目的地名 + 入口 Fragment 工厂」，
 * 模块内部实现不跨模块引用 —— 这是 24 模块划分能真正隔离的前提。
 *
 * 完整界面实现见《03_功能规格书》对应模块章节；M1 阶段先保证可编译、可导航。
 */
object HomeDestination {
    const val ROUTE: String = "feature/home"
}
