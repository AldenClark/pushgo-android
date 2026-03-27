package io.ethan.pushgo.notifications

import android.content.Context
import io.ethan.pushgo.R

object PrivateTransportStatusPresenter {
    fun summarize(
        context: Context,
        transport: String,
        stage: String,
    ): String {
        return summarize(
            context = context,
            privateModeEnabled = true,
            route = "private",
            transport = transport,
            stage = stage,
            networkAvailable = true,
        )
    }

    fun summarize(
        context: Context,
        privateModeEnabled: Boolean,
        route: String,
        transport: String,
        stage: String,
        networkAvailable: Boolean,
        keepaliveState: KeepaliveState = KeepaliveState.APP_FOREGROUND,
    ): String {
        if (!privateModeEnabled) {
            return context.getString(R.string.private_transport_status_disconnected)
        }
        val normalizedTransport = transport.trim().lowercase()
        val normalizedStage = stage.trim().lowercase()
        val normalizedRoute = route.trim().lowercase()
        if (keepaliveState == KeepaliveState.FGS_LOST) {
            return context.getString(R.string.private_transport_status_keepalive_lost)
        }
        if (!networkAvailable || normalizedStage == "offline_wait") {
            return context.getString(R.string.private_transport_status_network_unavailable)
        }
        if (normalizedRoute == "provider") {
            return context.getString(R.string.private_transport_status_disconnected)
        }
        return when (normalizedStage) {
            "connected" -> {
                val transportLabel = transportLabel(normalizedTransport)
                if (transportLabel == null) {
                    context.getString(R.string.private_transport_status_connected)
                } else {
                    context.getString(
                        R.string.private_transport_status_connected_with_transport,
                        transportLabel,
                    )
                }
            }
            "connecting" -> context.getString(R.string.private_transport_status_connecting)
            "reconnecting", "recovering", "goaway", "closed" -> {
                context.getString(R.string.private_transport_status_reconnecting)
            }
            "gateway_private_disabled" -> {
                context.getString(R.string.private_transport_status_gateway_disabled)
            }
            "backoff" -> context.getString(R.string.private_transport_status_waiting_retry)
            "offline_wait" -> context.getString(R.string.private_transport_status_network_unavailable)
            else -> context.getString(R.string.private_transport_status_disconnected)
        }
    }

    private fun transportLabel(transport: String): String? {
        return when (transport) {
            "wss" -> "WSS"
            "tcp" -> "TCP"
            "quic" -> "QUIC"
            else -> null
        }
    }
}
