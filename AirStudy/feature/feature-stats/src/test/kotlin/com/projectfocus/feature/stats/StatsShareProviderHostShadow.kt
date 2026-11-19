package com.projectfocus.feature.stats

import java.io.File
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Preserve the canonical-root boundary on the filesystem used by JVM tests. */
@Implements(className="androidx.core.content.FileProvider\$SimplePathStrategy",isInAndroidSdk=false)
class StatsShareProviderHostShadow {
    @Implementation protected fun belongsToRoot(path: String,root: String): Boolean =
        path.replace(File.separatorChar,'/').trimEnd('/').startsWith(root.replace(File.separatorChar,'/').trimEnd('/')+"/")
}
