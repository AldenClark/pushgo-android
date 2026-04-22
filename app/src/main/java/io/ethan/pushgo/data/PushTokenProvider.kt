package io.ethan.pushgo.data

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PushTokenProvider {
    suspend fun fetchToken(timeoutMs: Long): String?
}

class FirebasePushTokenProvider : PushTokenProvider {
    override suspend fun fetchToken(timeoutMs: Long): String? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (cont.isActive) {
                            cont.resume(token)
                        }
                    }
                    .addOnFailureListener { error ->
                        if (cont.isActive) {
                            cont.resumeWithException(error)
                        }
                    }
                    .addOnCanceledListener {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("FCM token task cancelled")
                            )
                        }
                    }
            }
        }?.trim()?.ifEmpty { null }
    }
}
