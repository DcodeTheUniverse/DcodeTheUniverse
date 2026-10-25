package com.dcode.circle.protocol

import com.dcode.crypto.Bytes
import com.dcode.crypto.EcSignatures
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Circle ID inter-app contract.
 *
 * A client app (e.g. Vault) starts Circle ID for a result with an explicit intent. Circle ID verifies the
 * person with multiple factors and answers with a [SignedEnvelope] whose payload is a [CircleAssertion]
 * signed by Circle ID's hardware-backed identity key. On success the assertion carries the client's
 * release secret, sealed (ECIES) to the one-time key the client generated for this request.
 */
object CircleContract {
    const val PACKAGE = "com.dcode.circleid"
    const val ACTION_PAIR = "com.dcode.circleid.action.PAIR"
    const val ACTION_VERIFY = "com.dcode.circleid.action.VERIFY"
    const val EXTRA_REQUEST = "com.dcode.circleid.extra.REQUEST"
    const val EXTRA_RESPONSE = "com.dcode.circleid.extra.RESPONSE"
    const val PROTOCOL_VERSION = 1
    const val MAX_ASSERTION_AGE_MS = 120_000L
    const val NONCE_SIZE = 32
    const val RELEASE_SECRET_SIZE = 32

    /** Binds a sealed release secret to the requesting package and this exact request. */
    fun releaseContext(clientPackage: String, nonce: String): ByteArray =
        "circle-release|$clientPackage|$nonce".toByteArray(Charsets.UTF_8)
}

@Serializable
enum class RequestKind { PAIR, VERIFY }

@Serializable
data class CircleRequest(
    val version: Int = CircleContract.PROTOCOL_VERSION,
    val kind: RequestKind,
    val clientPackage: String,
    val clientName: String,
    val nonce: String,
    val ephemeralKey: String,
    val reason: String,
    val issuedAt: Long,
    val minAssurance: Int = 2,
)

@Serializable
enum class AssertionStatus { VERIFIED, REJECTED, CANCELLED, NOT_ENROLLED, NOT_PAIRED, ERROR }

@Serializable
data class FactorResult(
    val id: String,
    val label: String,
    val passed: Boolean,
    val score: Double,
    val detail: String = "",
)

@Serializable
data class CircleAssertion(
    val version: Int = CircleContract.PROTOCOL_VERSION,
    val kind: RequestKind,
    val status: AssertionStatus,
    val requestNonce: String,
    val clientPackage: String,
    val clientCertSha256: String,
    val circleId: String,
    val identityPublicKey: String,
    val issuedAt: Long,
    val assurance: Int,
    val confidence: Double,
    val factors: List<FactorResult> = emptyList(),
    val sealedSecret: String? = null,
    val message: String? = null,
)

@Serializable
data class SignedEnvelope(
    val payload: String,
    val signature: String,
    val attestationChain: List<String> = emptyList(),
)

object CircleCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeRequest(r: CircleRequest): ByteArray = json.encodeToString(CircleRequest.serializer(), r).toByteArray()

    fun decodeRequest(bytes: ByteArray): CircleRequest =
        json.decodeFromString(CircleRequest.serializer(), bytes.toString(Charsets.UTF_8))

    /** Serialises the assertion and signs the exact bytes, so no canonicalisation is needed on the verifier side. */
    fun sealAssertion(a: CircleAssertion, chain: List<ByteArray> = emptyList(), signer: (ByteArray) -> ByteArray): ByteArray {
        val payload = json.encodeToString(CircleAssertion.serializer(), a).toByteArray()
        val env = SignedEnvelope(Bytes.b64(payload), Bytes.b64(signer(payload)), chain.map(Bytes::b64))
        return json.encodeToString(SignedEnvelope.serializer(), env).toByteArray()
    }

    fun decodeEnvelope(bytes: ByteArray): SignedEnvelope =
        json.decodeFromString(SignedEnvelope.serializer(), bytes.toString(Charsets.UTF_8))

    fun decodeAssertion(env: SignedEnvelope): CircleAssertion =
        json.decodeFromString(CircleAssertion.serializer(), Bytes.unb64(env.payload).toString(Charsets.UTF_8))
}

object AssertionVerifier {
    sealed class Outcome {
        data class Valid(val assertion: CircleAssertion) : Outcome()
        data class Invalid(val reason: String) : Outcome()
    }

    /**
     * Verifies an envelope returned by Circle ID.
     * @param pinnedIdentityKey the identity key recorded at pairing time; null only while pairing (trust on first use,
     *   backed by the caller checking Circle ID's APK signing certificate).
     */
    fun verify(
        envelopeBytes: ByteArray,
        expected: CircleRequest,
        pinnedIdentityKey: ByteArray?,
        now: Long,
    ): Outcome {
        val env = try {
            CircleCodec.decodeEnvelope(envelopeBytes)
        } catch (e: Exception) {
            return Outcome.Invalid("Malformed response")
        }
        val assertion = try {
            CircleCodec.decodeAssertion(env)
        } catch (e: Exception) {
            return Outcome.Invalid("Malformed assertion")
        }
        val claimedKey = try {
            Bytes.unb64(assertion.identityPublicKey)
        } catch (e: Exception) {
            return Outcome.Invalid("Malformed identity key")
        }
        if (pinnedIdentityKey != null && !Bytes.constantTimeEquals(pinnedIdentityKey, claimedKey)) {
            return Outcome.Invalid("Identity key does not match the paired Circle ID")
        }
        val keyToUse = pinnedIdentityKey ?: claimedKey
        val sigOk = try {
            EcSignatures.verify(keyToUse, Bytes.unb64(env.payload), Bytes.unb64(env.signature))
        } catch (e: Exception) {
            false
        }
        if (!sigOk) return Outcome.Invalid("Signature verification failed")
        if (assertion.version != CircleContract.PROTOCOL_VERSION) return Outcome.Invalid("Unsupported protocol version")
        if (assertion.kind != expected.kind) return Outcome.Invalid("Assertion kind mismatch")
        if (!Bytes.constantTimeEquals(assertion.requestNonce.toByteArray(), expected.nonce.toByteArray())) {
            return Outcome.Invalid("Nonce mismatch (possible replay)")
        }
        if (assertion.clientPackage != expected.clientPackage) return Outcome.Invalid("Assertion issued to another app")
        if (assertion.circleId != EcSignatures.fingerprint(claimedKey)) return Outcome.Invalid("Circle ID does not match key")
        val age = now - assertion.issuedAt
        if (age > CircleContract.MAX_ASSERTION_AGE_MS || age < -30_000L) return Outcome.Invalid("Assertion expired")
        return Outcome.Valid(assertion)
    }
}
