package com.dcode.circle.protocol

import com.dcode.crypto.Bytes
import com.dcode.crypto.EcSignatures
import com.dcode.crypto.Ecies
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolTest {
    private val identity = Ecies.generateKeyPair()
    private val identityPub = identity.public.encoded
    private val clientEph = Ecies.generateKeyPair()
    private val now = 1_800_000_000_000L

    private fun request(kind: RequestKind = RequestKind.VERIFY) = CircleRequest(
        kind = kind,
        clientPackage = "com.dcode.vault",
        clientName = "Vault",
        nonce = Bytes.b64(Bytes.random(32)),
        ephemeralKey = Bytes.b64(clientEph.public.encoded),
        reason = "Unlock",
        issuedAt = now,
    )

    private fun respond(req: CircleRequest, secret: ByteArray, mutate: (CircleAssertion) -> CircleAssertion = { it }): ByteArray {
        val sealed = Ecies.seal(Bytes.unb64(req.ephemeralKey), secret, CircleContract.releaseContext(req.clientPackage, req.nonce))
        val a = CircleAssertion(
            kind = req.kind,
            status = AssertionStatus.VERIFIED,
            requestNonce = req.nonce,
            clientPackage = req.clientPackage,
            clientCertSha256 = "00",
            circleId = EcSignatures.fingerprint(identityPub),
            identityPublicKey = Bytes.b64(identityPub),
            issuedAt = now + 1000,
            assurance = 3,
            confidence = 0.999,
            sealedSecret = Bytes.b64(sealed),
        )
        return CircleCodec.sealAssertion(mutate(a)) { EcSignatures.sign(identity.private, it) }
    }

    @Test
    fun validAssertionReleasesSecret() {
        val req = CircleCodec.decodeRequest(CircleCodec.encodeRequest(request()))
        val secret = Bytes.random(32)
        val outcome = AssertionVerifier.verify(respond(req, secret), req, identityPub, now + 2000)
        val valid = assertIs<AssertionVerifier.Outcome.Valid>(outcome)
        val opened = Ecies.open(clientEph, Bytes.unb64(valid.assertion.sealedSecret!!), CircleContract.releaseContext(req.clientPackage, req.nonce))
        assertContentEquals(secret, opened)
    }

    @Test
    fun rejectsReplayForgeryAndWrongKey() {
        val req = request()
        val good = respond(req, Bytes.random(32))
        // Replay against a new request (different nonce).
        assertIs<AssertionVerifier.Outcome.Invalid>(AssertionVerifier.verify(good, request(), identityPub, now))
        // Expired.
        assertIs<AssertionVerifier.Outcome.Invalid>(AssertionVerifier.verify(good, req, identityPub, now + 10 * 60_000))
        // Signed by an impostor key while the real one is pinned.
        val impostor = Ecies.generateKeyPair()
        val forged = CircleCodec.sealAssertion(CircleCodec.decodeAssertion(CircleCodec.decodeEnvelope(good))) {
            EcSignatures.sign(impostor.private, it)
        }
        assertIs<AssertionVerifier.Outcome.Invalid>(AssertionVerifier.verify(forged, req, identityPub, now))
        // Pair assertion presented for verify.
        val pairResp = respond(req, Bytes.random(32)) { it.copy(kind = RequestKind.PAIR) }
        assertIs<AssertionVerifier.Outcome.Invalid>(AssertionVerifier.verify(pairResp, req, identityPub, now))
        // Different client package.
        val other = respond(req, Bytes.random(32)) { it.copy(clientPackage = "evil.app") }
        assertIs<AssertionVerifier.Outcome.Invalid>(AssertionVerifier.verify(other, req, identityPub, now))
    }

    @Test
    fun pairingTrustsKeyInAssertion() {
        val req = request(RequestKind.PAIR)
        val outcome = AssertionVerifier.verify(respond(req, Bytes.random(32)), req, null, now)
        assertEquals(RequestKind.PAIR, assertIs<AssertionVerifier.Outcome.Valid>(outcome).assertion.kind)
    }
}
