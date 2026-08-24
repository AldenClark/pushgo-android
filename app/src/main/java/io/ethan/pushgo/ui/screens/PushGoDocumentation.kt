package io.ethan.pushgo.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.net.URI
import java.util.Locale

enum class PushGoDocumentationPage(val path: String) {
    GETTING_STARTED("/guides/getting-started/"),
    MESSAGE_API("/reference/api-message/"),
    E2EE("/reference/e2ee/"),
}

object PushGoDocumentation {
    private const val DOCUMENTATION_HOST = "pushgo.dev"

    fun urlFor(
        page: PushGoDocumentationPage,
        languageTag: String = Locale.getDefault().toLanguageTag(),
    ): String {
        val localizedPrefix = if (languageTag.trim().lowercase().startsWith("zh")) "/zh" else ""
        return "https://$DOCUMENTATION_HOST$localizedPrefix${page.path}"
    }

    internal fun isAllowedHttpsUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(DOCUMENTATION_HOST, ignoreCase = true) &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawPath?.startsWith('/') == true
    }

    fun open(
        context: Context,
        page: PushGoDocumentationPage,
    ): Boolean {
        val url = urlFor(page)
        if (!isAllowedHttpsUrl(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
