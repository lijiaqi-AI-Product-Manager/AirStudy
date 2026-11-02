package com.projectfocus.feature.onboarding.permission















class TestPermissionPlatform(
    var statuses: MutableMap<PermissionItem, PermissionStatus> =
        PermissionItem.entries.associateWith { PermissionStatus.DENIED }.toMutableMap(),
    /** 是否需要走运行时权限请求（通知权限为 true） */
    var runtimeForNotification: Boolean = false,
    /** 是否已被永久拒绝 */
    var permanentlyDenied: Boolean = false,
) : PermissionPlatform {

    /** 记录每次被点击的项，用于断言"点了哪个" */
    val opened: MutableList<PermissionItem> = mutableListOf()

    override fun status(item: PermissionItem): PermissionStatus =
        statuses.getValue(item)

    override fun settingsIntent(item: PermissionItem): android.content.Intent {
        opened += item
        return android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
    }

    override fun needsRuntimeRequest(item: PermissionItem): Boolean =
        item == PermissionItem.NOTIFICATION && runtimeForNotification

    override fun isPermanentlyDenied(item: PermissionItem): Boolean = permanentlyDenied
}
