package com.streamy.app.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.streamy.app.R

/**
 * Locale-independent text the VM can emit. The VM outlives configuration changes, so it
 * carries resource ids + args rather than resolved strings — the UI resolves them against
 * the *current* configuration, so a language switch mid-session never shows stale copy.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    fun resolve(context: Context): String = when (this) {
        // No-arg path skips String.format so a literal '%' in a translation can't throw.
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
    }
}

/** Optional snackbar action. Resolved by the nav host, which has an Activity context. */
enum class UserAction(@StringRes val labelRes: Int) {
    /** Deep-link to this app's system settings page (permanently denied mic permission). */
    OpenAppSettings(R.string.action_open_settings),

    /** Open the system "install unknown apps" page (user must grant before the APK can install). */
    OpenInstallPermissions(R.string.action_open_settings),
}

/** One-shot snackbar payload surfaced via [StreamyViewModel.userMessage]. */
data class UserMessage(val text: UiText, val action: UserAction? = null)
