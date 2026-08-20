package io.github.yylsping.xadfree;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Stable target-code identity for the installed X package.
 *
 * <p>The stable token is derived from the package name, the installed APK set
 * sizes (base + splits), the signing certificate hash and the versionCode —
 * versionCode as a cache-invalidation factor only, never for version-specific
 * behavior. It changes when the installed target code changes and survives
 * process restarts, randomized install paths and reinstalls of the same build.
 *
 * <p>Signing certificates need {@link PackageManager#GET_SIGNING_CERTIFICATES}
 * on API 28+ (P1-6); without the flag the system hands back null signing
 * info, which previously degraded the hash to the digest of an empty input.
 * When signatures are genuinely unavailable the hash degrades to an explicit
 * {@code unavailable} marker — the rest of the token still distinguishes
 * targets, and caching fails open.
 */
final class XTargetIdentity {
    private static final String UNAVAILABLE_SIGNER = "unavailable";

    final String packageName;
    final String apkPath;
    final long apkSize;
    final String signingHash;
    final String token;

    // Logging/diagnostics only.
    final long versionCode;
    final String versionName;

    XTargetIdentity(String packageName, String apkPath, long apkSize,
                    String signingHash, String token,
                    long versionCode, String versionName) {
        this.packageName = packageName;
        this.apkPath = apkPath;
        this.apkSize = apkSize;
        this.signingHash = signingHash;
        this.token = token;
        this.versionCode = versionCode;
        this.versionName = versionName;
    }

    static XTargetIdentity compute(Context appContext, String targetPackage) {
        long versionCode = -1L;
        String versionName = "unknown";
        String signingHash = "";
        try {
            PackageInfo packageInfo = appContext.getPackageManager()
                    .getPackageInfo(targetPackage, packageInfoFlags());
            versionCode = packageInfo.getLongVersionCode();
            versionName = packageInfo.versionName;
            signingHash = sha256(signingCertificateBytes(packageInfo));
        } catch (Throwable ignored) {
            // Fail-safe: identity degrades to size+version factors.
        }

        ApplicationInfo info = appContext.getApplicationInfo();
        String apkPath = info == null ? "" : String.valueOf(info.sourceDir);
        File apk = new File(apkPath);
        long size = apk.isFile() ? apk.length() : -1L;

        long[] splitSizes = null;
        if (info != null && info.splitSourceDirs != null) {
            splitSizes = new long[info.splitSourceDirs.length];
            for (int i = 0; i < info.splitSourceDirs.length; i++) {
                File splitFile = new File(info.splitSourceDirs[i]);
                splitSizes[i] = splitFile.isFile() ? splitFile.length() : -1L;
            }
        }

        return new XTargetIdentity(
                targetPackage,
                apkPath,
                size,
                signingHash,
                stableToken(targetPackage, size, splitSizes, signingHash, versionCode),
                versionCode,
                versionName);
    }

    /** API-28+ signing-certificate query flag (P1-6). */
    static int packageInfoFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : 0;
    }

    /** Deterministic plain-text token so JVM tests can reproduce it exactly. */
    static String stableToken(String packageName, long apkSize, long[] splitSizes,
                              String signingHash, long versionCode) {
        StringBuilder sb = new StringBuilder(packageName).append('|').append(apkSize);
        if (splitSizes != null) {
            for (long splitSize : splitSizes) {
                sb.append('|').append(splitSize);
            }
        }
        sb.append('|').append(signingHash == null ? "" : signingHash)
                .append('|').append(versionCode);
        return sb.toString();
    }

    boolean sameTarget(XTargetIdentity other) {
        return other != null
                && token != null && token.equals(other.token)
                && packageName.equals(other.packageName)
                && apkSize == other.apkSize;
    }

    String describe() {
        return "identity=" + shortToken()
                + " pkg=" + packageName
                + " apk=" + apkPath
                + " size=" + apkSize
                + " cert=" + (signingHash == null ? "null"
                : signingHash.substring(0, Math.min(12, signingHash.length())))
                + " [log]version=" + versionName + "(" + versionCode + ")";
    }

    String shortToken() {
        return token == null ? "null" : token.substring(0, Math.min(24, token.length()));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("package", packageName);
        json.put("apkPath", apkPath);
        json.put("apkSize", apkSize);
        json.put("signingHash", String.valueOf(signingHash));
        json.put("token", String.valueOf(token));
        json.put("versionCode", versionCode);
        json.put("versionName", String.valueOf(versionName));
        return json;
    }

    static XTargetIdentity fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new XTargetIdentity(
                json.optString("package", ""),
                json.optString("apkPath", ""),
                json.optLong("apkSize", -1L),
                json.optString("signingHash", ""),
                json.optString("token", ""),
                json.optLong("versionCode", -1L),
                json.optString("versionName", "unknown"));
    }

    /**
     * Deterministic digest over all signers (order-independent) so a rotated
     * signing history does not flap the cache token between launches.
     */
    static byte[] signingCertificateBytes(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return UNAVAILABLE_SIGNER.getBytes(StandardCharsets.UTF_8);
        }
        try {
            List<byte[]> certificates = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo signingInfo = packageInfo.signingInfo;
                if (signingInfo != null) {
                    appendCertificates(certificates, signingInfo.getApkContentsSigners());
                    if (signingInfo.hasMultipleSigners()) {
                        appendCertificates(certificates, signingInfo.getSigningCertificateHistory());
                    }
                }
            }
            if (certificates.isEmpty()) {
                appendCertificates(certificates, packageInfo.signatures);
            }
            if (certificates.isEmpty()) {
                return UNAVAILABLE_SIGNER.getBytes(StandardCharsets.UTF_8);
            }
            List<byte[]> ordered = new ArrayList<>(certificates);
            ordered.sort(XTargetIdentity::compareBytes);
            int total = 0;
            for (byte[] certificate : ordered) {
                total += certificate.length;
            }
            byte[] all = new byte[total];
            int offset = 0;
            for (byte[] certificate : ordered) {
                System.arraycopy(certificate, 0, all, offset, certificate.length);
                offset += certificate.length;
            }
            return all;
        } catch (Throwable ignored) {
            return UNAVAILABLE_SIGNER.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static void appendCertificates(List<byte[]> out, Signature[] signatures) {
        if (signatures == null) {
            return;
        }
        for (Signature signature : signatures) {
            if (signature != null) {
                byte[] bytes = signature.toByteArray();
                if (bytes != null && bytes.length > 0) {
                    out.add(bytes);
                }
            }
        }
    }

    /** Unsigned lexicographic byte[] order (Arrays.compare is API 33+). */
    private static int compareBytes(byte[] left, byte[] right) {
        int limit = Math.min(left.length, right.length);
        for (int index = 0; index < limit; index++) {
            int l = left[index] & 0xff;
            int r = right[index] & 0xff;
            if (l != r) {
                return l - r;
            }
        }
        return left.length - right.length;
    }

    static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
