package io.ethan.pushgo.ui.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import io.ethan.pushgo.R
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.GatewayErrorCategory
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun Throwable.toUiErrorMessage(@StringRes fallbackResId: Int): UiMessage {
    if (this is ChannelSubscriptionException) {
        if (matchesCode("private_channel_disabled")) {
            return ResMessage(R.string.error_gateway_private_disabled_use_fcm)
        }
        if (matchesCode("channel_password_missing")) {
            return ResMessage(R.string.error_channel_password_missing)
        }
        gatewayCodeResId()?.let { resId ->
            return ResMessage(resId)
        }
        localizedMessageText?.trim()?.takeIf { it.isNotEmpty() }?.let { message ->
            return TextMessage(message)
        }
        return when (category) {
            GatewayErrorCategory.NETWORK -> ResMessage(R.string.private_transport_status_network_unavailable)
            GatewayErrorCategory.AUTH -> ResMessage(R.string.error_gateway_auth_failed)
            GatewayErrorCategory.PERMISSION -> ResMessage(R.string.error_gateway_permission_denied)
            GatewayErrorCategory.RATE_LIMIT -> ResMessage(R.string.error_gateway_rate_limited)
            GatewayErrorCategory.TOO_BUSY -> ResMessage(R.string.error_gateway_temporarily_unavailable)
            GatewayErrorCategory.UPSTREAM -> ResMessage(R.string.error_gateway_upstream_unavailable)
            GatewayErrorCategory.INTERNAL -> ResMessage(R.string.error_gateway_temporarily_unavailable)
            GatewayErrorCategory.CONFLICT -> ResMessage(fallbackResId)
            GatewayErrorCategory.NOT_FOUND -> ResMessage(R.string.error_gateway_resource_not_found)
            GatewayErrorCategory.VALIDATION -> ResMessage(R.string.error_gateway_validation_failed)
            GatewayErrorCategory.LOCAL -> ResMessage(R.string.error_gateway_local_operation_failed)
            GatewayErrorCategory.FEATURE_DISABLED -> ResMessage(R.string.error_gateway_feature_unavailable)
            null -> if (isNetworkFailure()) {
                ResMessage(R.string.private_transport_status_network_unavailable)
            } else {
                ResMessage(fallbackResId)
            }
        }
    }

    return if (isNetworkFailure()) {
        ResMessage(R.string.private_transport_status_network_unavailable)
    } else {
        ResMessage(fallbackResId)
    }
}

private fun ChannelSubscriptionException.gatewayCodeResId(): Int? {
    return when {
        matchesCode("authentication_failed") -> R.string.error_gateway_auth_failed
        matchesCode("channel_not_found") -> R.string.error_gateway_channel_not_found
        matchesCode("channel_id_required") -> R.string.error_channel_id_required
        matchesCode("invalid_channel_id") -> R.string.error_channel_id_invalid
        matchesCode("channel_name_required") || matchesCode("invalid_channel_name") ->
            R.string.error_channel_name_required
        matchesCode("invalid_password") -> R.string.error_channel_password_length
        matchesCode("password_required") -> R.string.error_channel_password_missing
        matchesCode("password_mismatch") || matchesCode("invalid_channel_password") ->
            R.string.error_gateway_channel_password_incorrect
        matchesCode("channel_subscriber_limit_exceeded") ->
            R.string.error_gateway_channel_subscriber_limit_exceeded
        matchesCode("provider_token_missing") || matchesCode("provider_token_required") ->
            R.string.error_gateway_validation_failed
        matchesCode("device_key_not_found") -> R.string.error_gateway_device_registration_stale
        matchesCode("device_not_found") -> R.string.error_gateway_device_route_stale
        matchesCode("route_not_found") -> R.string.error_gateway_route_not_found
        matchesCode("event_missing_channel_id") -> R.string.error_event_missing_channel
        else -> null
    }
}

fun Throwable.toUserFacingText(context: Context, @StringRes fallbackResId: Int): String {
    return when (val message = toUiErrorMessage(fallbackResId)) {
        is ResMessage -> context.getString(message.resId)
        is TextMessage -> message.text
        is PluralResMessage -> context.resources.getQuantityString(
            message.resId,
            message.quantity,
            *message.args.toTypedArray(),
        )
    }
}

private fun Throwable.isNetworkFailure(): Boolean {
    if (this is UnknownHostException ||
        this is ConnectException ||
        this is SocketTimeoutException ||
        this is SocketException ||
        this is SSLException ||
        this is InterruptedIOException
    ) {
        return true
    }
    return cause?.let { it !== this && it.isNetworkFailure() } == true
}
