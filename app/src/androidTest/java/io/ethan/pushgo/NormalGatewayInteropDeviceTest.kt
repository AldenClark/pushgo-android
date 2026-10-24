package io.ethan.pushgo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the explicitly provisioned, task-owned normal local Gateway context. */
@RunWith(AndroidJUnit4::class)
class NormalGatewayInteropDeviceTest {
    @Test
    fun actualNormalGatewaySettlesOnlyDurablySavedAndroidContent() = kotlinx.coroutines.runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("pushgo.normalGatewayContextPath")
        assumeTrue("No task-owned normal Gateway supplied; this is NOT RUN", path != null)
        require(requireNotNull(path).matches(Regex("/data/local/tmp/pushgo-delivery-[A-Za-z0-9_-]+\\.json")))
        val context = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cat $path"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val scenario = ProviderV2PullOnlyDeviceTest()
        scenario.setUp()
        try {
            val receipt = scenario.verifyNormalGateway(context)
            val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: File(instrument.targetContext.externalMediaDirs.first(), "additional_test_output").absolutePath).apply { mkdirs() }
            File(output, "normal-gateway-android-${UUID.randomUUID()}.json").writeText(receipt.toString(2))
        } finally { scenario.tearDown() }
    }
}
