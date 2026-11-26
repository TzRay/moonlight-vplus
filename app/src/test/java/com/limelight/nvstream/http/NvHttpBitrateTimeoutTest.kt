package com.limelight.nvstream.http

import java.io.IOException
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NvHttpBitrateTimeoutTest {
    @Test(timeout = 10000) fun feedbackWireDistinguishesIdleFromLegacyZeroLoss() {
        withHost { host ->
            val http = host.client()
            host.warmUp(http)
            val idle = AbrPacketSample(0, 0, 1, 2, false, true)
            for (packets in listOf(idle, null)) {
                host.https.enqueue(MockResponse.Builder().body("{}").build())
                http.reportNetworkFeedback(NetworkFeedback(0f, 9, 2f, 1, 20000, packets))
                val request = host.https.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/api/abr/feedback", request.url.encodedPath)
                val body = org.json.JSONObject(request.body!!.utf8())
                assertEquals(packets == null, body.has("packetLoss"))
                assertEquals(packets != null, body.has("packetSample"))
                assertEquals(1, body.getInt("droppedFrames"))
                if (packets != null) {
                    val sample = body.getJSONObject("packetSample")
                    assertEquals(1L, sample.getLong("coverageGaps"))
                    assertEquals(false, sample.getBoolean("trusted"))
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun slowResponseIsBoundedAndNextAdjustmentSucceeds() {
        withHost { host ->
            val http = host.client()
            host.warmUp(http)
            // Bytes arrive within the read timeout; only the total deadline can stop this body.
            host.https.enqueue(MockResponse.Builder().body(BITRATE_BODY)
                .throttleBody(1, 50, TimeUnit.MILLISECONDS).build())

            assertBoundedFailure { http.setBitrate(8_000) }

            val request = host.https.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/bitrate", request.url.encodedPath)
            assertEquals("8000", request.url.queryParameter("bitrate"))
            host.https.enqueue(MockResponse.Builder().body(BITRATE_BODY).build())
            assertTrue(http.setBitrate(12_000))
        }
    }

    @Test(timeout = 10_000)
    fun unknownHttpsPortDiscoverySharesAdjustmentDeadline() {
        withHost { host ->
            val http = host.client(httpsPort = 0)
            host.http.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

            assertBoundedFailure { http.setBitrate(8_000) }

            val discovery = host.http.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull("The host must receive the HTTPS-port discovery request", discovery)
            assertEquals("/serverinfo", discovery!!.url.encodedPath)
            assertEquals(0, host.https.requestCount)
        }
    }

    private fun assertBoundedFailure(request: () -> Boolean) {
        val started = System.nanoTime()
        assertThrows(IOException::class.java) { request() }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("The adjustment took ${elapsedMs}ms", elapsedMs < 1_500)
    }

    private fun withHost(test: (Host) -> Unit) {
        MockWebServer().use { http ->
            MockWebServer().use { https ->
                val serverIdentity = certificate("host")
                val certificates = HandshakeCertificates.Builder()
                    .heldCertificate(serverIdentity)
                    .build()
                https.useHttps(certificates.sslSocketFactory())
                http.start()
                https.start()
                test(Host(http, https, serverIdentity))
            }
        }
    }

    private class Host(
        val http: MockWebServer,
        val https: MockWebServer,
        private val identity: HeldCertificate
    ) {
        fun client(httpsPort: Int = https.port): NvHTTP = NvHTTP(
            ComputerDetails.AddressTuple(http.hostName, http.port),
            httpsPort,
            "test-client-id",
            "test-client",
            identity.certificate,
            TestCryptoProvider(certificate("client")),
            NvHTTP.TimeoutConfig(
                connectTimeoutMs = 1_000,
                readTimeoutMs = 5_000,
                shortConnectTimeoutMs = 1_000,
                bitrateCallTimeoutMs = 250
            )
        )

        fun warmUp(client: NvHTTP) {
            https.enqueue(MockResponse.Builder().body(
                "<root status_code=\"200\"><appversion>7.1.0</appversion>" +
                    "<HttpsPort>${https.port}</HttpsPort></root>"
            ).build())
            client.getServerInfo(true)
            assertEquals("/serverinfo", https.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
        }
    }

    private class TestCryptoProvider(private val identity: HeldCertificate) : LimelightCryptoProvider {
        override fun getClientCertificate(): X509Certificate = identity.certificate

        override fun getClientPrivateKey(): PrivateKey = identity.keyPair.private

        override fun getPemEncodedClientCertificate(): ByteArray =
            identity.certificatePem().toByteArray(Charsets.UTF_8)

        override fun encodeBase64String(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)
    }

    private companion object {
        const val BITRATE_BODY = "<root status_code=\"200\"><bitrate>1</bitrate></root>"

        fun certificate(commonName: String): HeldCertificate = HeldCertificate.Builder()
            .commonName(commonName)
            .addSubjectAlternativeName("localhost")
            .build()
    }
}
