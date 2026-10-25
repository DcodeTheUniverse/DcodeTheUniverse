package com.dcode.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id parameters. Memory is in KiB. */
data class Argon2Params(
    val memoryKiB: Int = 64 * 1024,
    val iterations: Int = 3,
    val parallelism: Int = 2,
) {
    init {
        require(memoryKiB >= 8 * parallelism) { "memory too small" }
        require(iterations >= 1 && parallelism >= 1)
    }
}

/** Memory-hard password hashing (Argon2id, RFC 9106) via Bouncy Castle's pure-Java implementation. */
object PasswordKdf {
    fun argon2id(password: CharArray, salt: ByteArray, params: Argon2Params, length: Int = 32): ByteArray {
        val pw = Bytes.utf8(password)
        try {
            val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .build()
            val gen = Argon2BytesGenerator()
            gen.init(p)
            val out = ByteArray(length)
            gen.generateBytes(pw, out)
            return out
        } finally {
            Bytes.wipe(pw)
        }
    }
}
