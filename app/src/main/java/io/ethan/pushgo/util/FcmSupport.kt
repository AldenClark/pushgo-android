package io.ethan.pushgo.util

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp

object FcmSupport {
    fun isAvailable(context: Context): Boolean {
        val playServicesReady =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        if (!playServicesReady) {
            return false
        }

        val app = FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(context)
            ?: return false
        val options = app.options
        val hasApplicationId = options.applicationId.isNotBlank()
        val hasSenderId = options.gcmSenderId?.isNotBlank() == true
        return hasApplicationId && hasSenderId
    }
}
