package io.ethan.pushgo.testing

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.ui.screens.positionMessageRefreshResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageRefreshThreadInstrumentedTest {
    @Test
    fun diskThreadRefreshCompletionPositionsTheListOnMain() = runBlocking {
        withContext(Dispatchers.IO) {
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            positionMessageRefreshResult(2) { index ->
                assertEquals(2, index)
                assertEquals(Looper.getMainLooper().thread, Thread.currentThread())
            }
        }
    }
}
