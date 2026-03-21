package io.ethan.pushgo.util

object SilentSink {
    fun v(tag: String, msg: String): Int = 0
    fun d(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String, tr: Throwable): Int = 0
    fun e(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String, tr: Throwable): Int = 0
}
