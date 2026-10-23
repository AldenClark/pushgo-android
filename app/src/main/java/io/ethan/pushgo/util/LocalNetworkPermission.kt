package io.ethan.pushgo.util

import androidx.annotation.RequiresApi
import android.content.Context
import android.content.pm.PackageManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.os.Build
import android.os.CancellationSignal
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.GatewayErrorCategory
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android 17 gates local destinations, including public names resolving to LAN addresses. */
object LocalNetworkPermission {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val ERROR_CODE = "local_network_permission_required"

    fun isRequired(sdk: Int, target: Int): Boolean = sdk >= 37 && target >= 37

    fun isGranted(context: Context): Boolean =
        !isRequired(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion) ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    internal fun isLocal(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)

    /** Call on IO only. DNS is allowed without LAN permission; timeout cancels the OS query. */
    fun requireAccess(context: Context, endpoint: String) {
        if (Build.VERSION.SDK_INT < 37 || isGranted(context)) return
        val host = URL(endpoint).host.removeSurrounding("[", "]").trimEnd('.')
        if (host.endsWith(".local", ignoreCase = true)) {
            throw permissionRequired()
        }
        val numeric = runCatching { InetAddresses.parseNumericAddress(host) }.getOrNull()
        val addresses = if (numeric != null) listOf(numeric) else resolve(context, host)
        requireDestinationAccess(false) { addresses }
    }

    internal fun requireDestinationAccess(granted: Boolean, resolve: () -> List<InetAddress>) {
        if (!granted && resolve().any(::isLocal)) {
            throw permissionRequired()
        }
    }

    private fun permissionRequired() = ChannelSubscriptionException.local(
        message = "Local network permission is required for this Gateway",
        code = ERROR_CODE,
        category = GatewayErrorCategory.PERMISSION,
    )

    @RequiresApi(37)
    private fun resolve(context: Context, host: String): List<InetAddress> {
        val completed = CountDownLatch(1)
        val cancellation = CancellationSignal()
        var result: List<InetAddress> = emptyList()
        var failure: Exception? = null
        DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_EMPTY,
            context.mainExecutor, cancellation, object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    result = answer
                    completed.countDown()
                }
                override fun onError(error: DnsResolver.DnsException) {
                    failure = error
                    completed.countDown()
                }
            })
        try {
            if (!completed.await(3, TimeUnit.SECONDS)) throw SocketTimeoutException("Gateway DNS timed out")
            failure?.let { throw java.net.UnknownHostException(host).apply { initCause(it) } }
            if (result.isEmpty()) throw java.net.UnknownHostException(host)
            return result
        } finally {
            cancellation.cancel()
        }
    }
}
