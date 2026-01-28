package io.ethan.pushgo.data.model

enum class KeyLength(val bytes: Int) {
    BITS_128(16),
    BITS_192(24),
    BITS_256(32),
}
