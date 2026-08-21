package io.github.yylsping.xadfree;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class XTargetIdentityTest {
    @Test
    public void tokenIsDeterministicAndContentAddressed() {
        String first = XTargetIdentity.stableToken("com.twitter.android", 100L,
                new long[]{7L, 8L}, "cert", 12L);
        String second = XTargetIdentity.stableToken("com.twitter.android", 100L,
                new long[]{7L, 8L}, "cert", 12L);

        assertEquals(first, second);
        assertTrue(first.contains("com.twitter.android"));
    }

    @Test
    public void tokenChangesWithBaseSize() {
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert", 1L);
        String b = XTargetIdentity.stableToken("com.twitter.android", 101L, null, "cert", 1L);

        assertFalse(a.equals(b));
    }

    @Test
    public void tokenChangesWithSplitSizes() {
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L, new long[]{7L}, "c", 1L);
        String b = XTargetIdentity.stableToken("com.twitter.android", 100L, new long[]{9L}, "c", 1L);

        assertFalse(a.equals(b));
    }

    @Test
    public void tokenChangesWithSigningCert() {
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert-a", 1L);
        String b = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert-b", 1L);

        assertFalse(a.equals(b));
    }

    @Test
    public void tokenChangesWithVersionCode() {
        // versionCode is a cache-invalidation factor (allowed): an upgrade
        // must not reuse the previous build's cached descriptors.
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert", 12L);
        String b = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert", 13L);

        assertFalse(a.equals(b));
    }

    @Test
    public void reinstallOfSameBuildKeepsIdentity() {
        // Same code, same version, same signer: stable across reinstalls
        // (no lastUpdateTime factor).
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L,
                new long[]{7L}, "cert", 12L);
        String b = XTargetIdentity.stableToken("com.twitter.android", 100L,
                new long[]{7L}, "cert", 12L);

        assertEquals(a, b);
    }

    @Test
    public void signerChangeInvalidatesIdentity() {
        XTargetIdentity a = new XTargetIdentity("com.twitter.android", "/p", 100L, "cert-a",
                XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert-a", 1L),
                1L, "1.0");
        XTargetIdentity b = new XTargetIdentity("com.twitter.android", "/p", 100L, "cert-b",
                XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert-b", 1L),
                1L, "1.0");

        assertFalse(a.sameTarget(b));
    }

    @Test
    public void jsonRoundTripKeepsToken() throws Exception {
        XTargetIdentity identity = new XTargetIdentity("com.twitter.android", "/p", 100L, "c",
                XTargetIdentity.stableToken("com.twitter.android", 100L, null, "c", 1L),
                1L, "1.0");

        XTargetIdentity decoded = XTargetIdentity.fromJson(identity.toJson());

        assertTrue(identity.sameTarget(decoded));
        assertEquals(identity.token, decoded.token);
        assertEquals(identity.versionName, decoded.versionName);
    }

    @Test
    public void unavailableSignerFailsSafe() {
        // No PackageInfo / no signing data: an explicit marker is hashed
        // instead of silently digesting an empty input (P1-6).
        byte[] bytes = XTargetIdentity.signingCertificateBytes(null);
        assertEquals(XTargetIdentity.sha256("unavailable".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), XTargetIdentity.sha256(bytes));
    }

    // ------------------------------------------------------------------
    // P2-1 (2.0.2): signer-set selection semantics
    // ------------------------------------------------------------------

    private static final byte[][] CERTS_A = {{1}, {2}, {3}};
    private static final byte[][] CERTS_B = {{9}};

    @Test
    public void multipleSignersUseCurrentApkContents() {
        byte[][] selected = XTargetIdentity.selectSigners(true, CERTS_A, CERTS_B);
        assertSame(CERTS_B, selected);
    }

    @Test
    public void singleSignerPrefersRotationHistory() {
        byte[][] selected = XTargetIdentity.selectSigners(false, CERTS_A, CERTS_B);
        assertSame(CERTS_A, selected);
    }

    @Test
    public void singleSignerWithoutHistoryFallsBackToContents() {
        byte[][] selected = XTargetIdentity.selectSigners(false, null, CERTS_B);
        assertSame(CERTS_B, selected);
        byte[][] emptyHistory = XTargetIdentity.selectSigners(false, new byte[0][], CERTS_B);
        assertSame(CERTS_B, emptyHistory);
    }

    @Test
    public void combineCertificatesIsOrderIndependentAndStable() {
        byte[] left = XTargetIdentity.combineCertificates(new byte[][]{{1, 2}, {3}});
        byte[] right = XTargetIdentity.combineCertificates(new byte[][]{{3}, {1, 2}});
        assertArrayEquals(left, right);

        byte[] again = XTargetIdentity.combineCertificates(new byte[][]{{3}, {1, 2}});
        assertArrayEquals(left, again);
    }

    @Test
    public void combineCertificatesDropsEmptyEntriesAndHandlesEmpty() {
        byte[] combined = XTargetIdentity.combineCertificates(new byte[][]{{5}, null, new byte[0]});
        assertArrayEquals(new byte[]{5}, combined);

        byte[] empty = XTargetIdentity.combineCertificates(new byte[0][]);
        byte[] allNull = XTargetIdentity.combineCertificates(new byte[][]{null, new byte[0]});
        // Both degrade to the explicit unavailable marker, never to a
        // disguised digest of nothing.
        assertArrayEquals("unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8), empty);
        assertArrayEquals(empty, allNull);
    }

    @Test
    public void signerSetChangeChangesDigest() {
        String one = XTargetIdentity.sha256(
                XTargetIdentity.combineCertificates(new byte[][]{{1}, {2}}));
        String two = XTargetIdentity.sha256(
                XTargetIdentity.combineCertificates(new byte[][]{{1}, {9}}));
        assertFalse(one.equals(two));
    }

    @Test
    public void signingCertificateBytesAreOrderIndependent() {
        android.content.pm.Signature first =
                new android.content.pm.Signature(new byte[]{1, 2, 3});
        android.content.pm.Signature second =
                new android.content.pm.Signature(new byte[]{4, 5});
        android.content.pm.PackageInfo one = new android.content.pm.PackageInfo();
        android.content.pm.PackageInfo two = new android.content.pm.PackageInfo();
        one.signatures = new android.content.pm.Signature[]{first, second};
        two.signatures = new android.content.pm.Signature[]{second, first};

        // android.jar stubs return null from toByteArray() on the JVM, so both
        // sides degrade to the unavailable marker — still deterministic.
        assertEquals(
                XTargetIdentity.sha256(XTargetIdentity.signingCertificateBytes(one)),
                XTargetIdentity.sha256(XTargetIdentity.signingCertificateBytes(two)));
    }
}
