package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class XTargetIdentityTest {
    @Test
    public void tokenIsDeterministicAndContentAddressed() {
        String first = XTargetIdentity.stableToken("com.twitter.android", 100L,
                new long[]{7L, 8L}, "cert");
        String second = XTargetIdentity.stableToken("com.twitter.android", 100L,
                new long[]{7L, 8L}, "cert");

        assertEquals(first, second);
        assertTrue(first.contains("com.twitter.android"));
    }

    @Test
    public void tokenChangesWithBaseSize() {
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert");
        String b = XTargetIdentity.stableToken("com.twitter.android", 101L, null, "cert");

        assertFalse(a.equals(b));
    }

    @Test
    public void tokenChangesWithSplitSizes() {
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L, new long[]{7L}, "c");
        String b = XTargetIdentity.stableToken("com.twitter.android", 100L, new long[]{9L}, "c");

        assertFalse(a.equals(b));
    }

    @Test
    public void tokenChangesWithSigningCert() {
        String a = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert-a");
        String b = XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert-b");

        assertFalse(a.equals(b));
    }

    @Test
    public void tokenIgnoresVersionCodeAndName() {
        // versionCode/versionName are log-only; two identities that differ
        // ONLY in version metadata share the same functional token.
        XTargetIdentity a = new XTargetIdentity("com.twitter.android", "/p", 100L, "c",
                XTargetIdentity.stableToken("com.twitter.android", 100L, null, "c"),
                312170000L, "12.17.0-release.0");
        XTargetIdentity b = new XTargetIdentity("com.twitter.android", "/p", 100L, "c",
                XTargetIdentity.stableToken("com.twitter.android", 100L, null, "c"),
                312180000L, "12.18.0-release.0");

        assertTrue(a.sameTarget(b));
    }

    @Test
    public void jsonRoundTripKeepsToken() throws Exception {
        XTargetIdentity identity = new XTargetIdentity("com.twitter.android", "/p", 100L, "c",
                XTargetIdentity.stableToken("com.twitter.android", 100L, null, "c"),
                1L, "1.0");

        XTargetIdentity decoded = XTargetIdentity.fromJson(identity.toJson());

        assertTrue(identity.sameTarget(decoded));
        assertEquals(identity.token, decoded.token);
        assertEquals(identity.versionName, decoded.versionName);
    }
}
