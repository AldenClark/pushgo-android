package io.ethan.pushgo.ui.viewmodel

import io.ethan.pushgo.R
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.GatewayErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayErrorUiTest {
    @Test
    fun channelNotFoundUsesSpecificMessage() {
        val error = ChannelSubscriptionException(
            message = "channel not found on gateway",
            code = "channel_not_found",
            category = GatewayErrorCategory.NOT_FOUND,
            localizedMessageText = null,
            detail = "channel not found on gateway",
            httpStatus = 404,
            retryable = false,
            requestId = null,
        )

        val message = error.toUiErrorMessage(R.string.error_private_channel_subscribe_failed)
        assertTrue(message is ResMessage)
        val res = message as ResMessage
        assertEquals(R.string.error_gateway_channel_not_found, res.resId)
    }

    @Test
    fun codeMappingWinsOverLocalizedMessageFallbackWhenKnown() {
        val error = ChannelSubscriptionException(
            message = "invalid channel password",
            code = "password_mismatch",
            category = GatewayErrorCategory.CONFLICT,
            localizedMessageText = "The channel password is incorrect. Please verify it and try again.",
            detail = "invalid channel password",
            httpStatus = 403,
            retryable = false,
            requestId = null,
        )

        val message = error.toUiErrorMessage(R.string.error_private_channel_subscribe_failed)
        assertTrue(message is ResMessage)
        val res = message as ResMessage
        assertEquals(R.string.error_gateway_channel_password_incorrect, res.resId)
    }

    @Test
    fun subscriberLimitUsesSpecificMessage() {
        val error = ChannelSubscriptionException(
            message = "channel subscriber limit exceeded",
            code = "channel_subscriber_limit_exceeded",
            category = GatewayErrorCategory.VALIDATION,
            localizedMessageText = null,
            detail = "channel subscriber limit exceeded",
            httpStatus = 400,
            retryable = false,
            requestId = null,
        )

        val message = error.toUiErrorMessage(R.string.error_private_channel_subscribe_failed)
        assertTrue(message is ResMessage)
        val res = message as ResMessage
        assertEquals(R.string.error_gateway_channel_subscriber_limit_exceeded, res.resId)
    }
}
