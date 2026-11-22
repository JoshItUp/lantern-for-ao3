package io.github.joshitup.lantern.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifierTest {
    private val pkg = "io.github.joshitup.lantern"
    private val cert = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun facts(pkgName: String = pkg, code: Long = 10001, certs: Set<String> = setOf(cert)) = ArchiveFacts(pkgName, code, certs)

    @Test fun samePackageHigherCodeMatchingCert_true() =
        assertTrue(ApkVerifier.verify(facts(), pkg, 10000, setOf(cert)))

    @Test fun differentPackage_false() =
        assertFalse(ApkVerifier.verify(facts(pkgName = "com.example.other"), pkg, 10000, setOf(cert)))

    @Test fun equalCode_false() = assertFalse(ApkVerifier.verify(facts(code = 10000), pkg, 10000, setOf(cert)))

    @Test fun lowerCode_false() = assertFalse(ApkVerifier.verify(facts(code = 9999), pkg, 10000, setOf(cert)))

    @Test fun certMismatch_false() {
        assertFalse(ApkVerifier.verify(facts(certs = setOf(other)), pkg, 10000, setOf(cert)))
        assertFalse(ApkVerifier.verify(facts(certs = setOf(cert, other)), pkg, 10000, setOf(cert)))
        assertFalse(ApkVerifier.verify(facts(certs = emptySet()), pkg, 10000, setOf(cert)))
    }

    @Test fun nullArchive_false() = assertFalse(ApkVerifier.verify(null, pkg, 10000, setOf(cert)))

    @Test fun emptyExpected_usesInstalledCerts() {
        assertEquals(setOf(other), ApkVerifier.expectedCerts("", setOf(other)))
        assertEquals(setOf(cert), ApkVerifier.expectedCerts(cert, setOf(other)))
        assertTrue(ApkVerifier.verify(facts(certs = setOf(other)), pkg, 10000, ApkVerifier.expectedCerts("", setOf(other))))
        assertFalse(ApkVerifier.verify(facts(certs = setOf(other)), pkg, 10000, ApkVerifier.expectedCerts(cert, setOf(other))))
        // Nothing to compare against never passes.
        assertFalse(ApkVerifier.verify(facts(certs = emptySet()), pkg, 10000, ApkVerifier.expectedCerts("", emptySet())))
    }
}
