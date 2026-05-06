package io.ethan.pushgo.testing

object InstrumentationRuntime {
    @Volatile
    private var underInstrumentationTest: Boolean = false

    fun markUnderInstrumentationTest() {
        underInstrumentationTest = true
    }

    fun isUnderInstrumentationTest(): Boolean = underInstrumentationTest
}
