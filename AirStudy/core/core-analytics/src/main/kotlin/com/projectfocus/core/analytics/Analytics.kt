package com.projectfocus.core.analytics


interface Analytics {









    fun track(event: AnalyticsEvent)


    fun setUserProperty(key: String, value: String?)

    /** 立即冲刷缓冲区（App 退到后台时调用，避免丢事件） */
    fun flush()

    /** 是否启用。关闭时所有调用都应被实现忽略 */
    val enabled: Boolean
}














object NoOpAnalytics : Analytics {
    override fun track(event: AnalyticsEvent) = Unit

    override fun setUserProperty(key: String, value: String?) = Unit

    override fun flush() = Unit

    override val enabled: Boolean get() = false
}












class FakeAnalytics(override val enabled: Boolean = true) : Analytics {

    private val _events = mutableListOf<AnalyticsEvent>()

    /** 已收集的事件（按调用顺序） */
    val events: List<AnalyticsEvent> get() = _events.toList()

    private val _properties = mutableMapOf<String, String?>()

    val properties: Map<String, String?> get() = _properties.toMap()

    var flushCount: Int = 0
        private set

    override fun track(event: AnalyticsEvent) {
        if (!enabled) return
        _events += event
    }

    override fun setUserProperty(key: String, value: String?) {
        if (!enabled) return
        _properties[key] = value
    }

    override fun flush() {
        flushCount++
    }

    fun clear() {
        _events.clear()
        _properties.clear()
        flushCount = 0
    }

    /** 按事件名筛选 */
    fun eventsNamed(name: String): List<AnalyticsEvent> = _events.filter { it.name == name }

    /** 断言用：最后一个事件的参数表 */
    fun lastParams(): Map<String, Any?>? = _events.lastOrNull()?.params()

    /** 断言用：某个事件名的全部参数表 */
    fun paramsOf(name: String): List<Map<String, Any?>> = eventsNamed(name).map { it.params() }
}
