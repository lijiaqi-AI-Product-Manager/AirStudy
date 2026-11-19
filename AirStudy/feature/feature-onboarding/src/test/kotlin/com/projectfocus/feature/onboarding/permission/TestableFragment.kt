package com.projectfocus.feature.onboarding.permission





















class TestableFragment : PermissionGuideFragment() {
















    override fun createViewModel(): PermissionGuideViewModel =
        PermissionGuideViewModel(TestFragmentFactory.take())
}
