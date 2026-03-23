package io.ethan.pushgo.util

object SilentSink {
    fun v(tag: String, msg: String): Int = emit(DiagnosticLogLevel.VERBOSE, tag, msg)
    fun d(tag: String, msg: String): Int = emit(DiagnosticLogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String): Int = emit(DiagnosticLogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String): Int = emit(DiagnosticLogLevel.WARN, tag, msg)
    fun w(tag: String, msg: String, tr: Throwable): Int = emit(DiagnosticLogLevel.WARN, tag, msg, tr)
    fun e(tag: String, msg: String): Int = emit(DiagnosticLogLevel.ERROR, tag, msg)
    fun e(tag: String, msg: String, tr: Throwable): Int = emit(DiagnosticLogLevel.ERROR, tag, msg, tr)

    private fun emit(
        level: DiagnosticLogLevel,
        tag: String,
        msg: String,
        throwable: Throwable? = null,
    ): Int {
        DiagnosticLogStore.record(
            level = level,
            tag = tag,
            message = msg,
            throwable = throwable,
        )
        return 0
    }
}
