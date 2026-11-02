package com.projectfocus.feature.record.share

import java.io.File
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Keeps the provider's canonical-root boundary on the host filesystem used by JVM tests. */
@Implements(className = "androidx.core.content.FileProvider\$SimplePathStrategy", isInAndroidSdk = false)
class HostFileProviderStrategyShadow {
    @Implementation
    protected fun belongsToRoot(path: String, root: String): Boolean =
        path.replace(File.separatorChar, '/').trimEnd('/').startsWith(root.replace(File.separatorChar, '/').trimEnd('/') + "/")
}
