package com.projectfocus.app.auth

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only the commit boundary is controlled; all reads/writes use real Robolectric SP. */
internal open class CommitBoundaryPreferences(protected val real: SharedPreferences) : SharedPreferences by real {
    val commits = AtomicInteger()
    override fun edit(): SharedPreferences.Editor = BoundaryEditor(real.edit())
    protected open fun commit(editor: SharedPreferences.Editor, attempt: Int): Boolean = editor.commit()

    private inner class BoundaryEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { editor.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { editor.putInt(key, value) }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { editor.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { editor.putBoolean(key, value) }
        override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { editor.clear() }
        override fun commit(): Boolean = this@CommitBoundaryPreferences.commit(editor, commits.incrementAndGet())
    }
}

internal class FailFirstCommitPreferences(real: SharedPreferences) : CommitBoundaryPreferences(real) {
    override fun commit(editor: SharedPreferences.Editor, attempt: Int): Boolean {
        val persisted = editor.commit()
        return if (attempt == 1) false else persisted
    }
}

/** Commit has changed real SP, but save has not yet published the TokenStore token. */
internal class HeldCommitPreferences(real: SharedPreferences) : CommitBoundaryPreferences(real) {
    val committed = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun commit(editor: SharedPreferences.Editor, attempt: Int): Boolean {
        val persisted = editor.commit()
        if (attempt == 1) {
            committed.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release committed preference write" }
        }
        return persisted
    }
}
