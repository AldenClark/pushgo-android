package io.ethan.pushgo.ui.viewmodel

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

sealed interface UiMessage {
    fun resolve(context: Context): String
}

data class ResMessage(@param:StringRes val resId: Int, val args: List<Any> = emptyList()) : UiMessage {
    override fun resolve(context: Context): String {
        return if (args.isEmpty()) {
            context.getString(resId)
        } else {
            context.getString(resId, *args.toTypedArray())
        }
    }
}

data class PluralResMessage(
    @param:PluralsRes val resId: Int,
    val quantity: Int,
    val args: List<Any> = emptyList(),
) : UiMessage {
    override fun resolve(context: Context): String {
        return if (args.isEmpty()) {
            context.resources.getQuantityString(resId, quantity)
        } else {
            context.resources.getQuantityString(resId, quantity, *args.toTypedArray())
        }
    }
}

data class TextMessage(val text: String) : UiMessage {
    override fun resolve(context: Context): String = text
}
