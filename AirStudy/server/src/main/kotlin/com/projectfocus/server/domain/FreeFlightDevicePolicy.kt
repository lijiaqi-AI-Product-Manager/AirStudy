package com.projectfocus.server.domain

/** Explicit Own policy; source grant size/reset/refund rules remain unknown. */
data class FreeFlightDevicePolicy(val initialUses: Long? = null, val policyId: String? = null) {
    init {
        require(initialUses == null || initialUses in 0..1_000_000L)
        require(initialUses == null || !policyId.isNullOrBlank())
        require(policyId == null || policyId.length <= 64)
    }
    companion object {
        fun fromEnv(getenv: (String) -> String? = System::getenv): FreeFlightDevicePolicy {
            val raw = getenv("PF_FREEFLIGHT_INITIAL_DEVICE_USES") ?: return FreeFlightDevicePolicy()
            val uses = raw.toLongOrNull() ?: error("Invalid PF_FREEFLIGHT_INITIAL_DEVICE_USES")
            val policy = getenv("PF_FREEFLIGHT_DEVICE_POLICY_ID")?.takeIf { it.isNotBlank() }
                ?: error("PF_FREEFLIGHT_DEVICE_POLICY_ID is required with explicit initial uses")
            return FreeFlightDevicePolicy(uses, policy)
        }
    }
}
