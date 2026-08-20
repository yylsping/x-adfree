package io.github.yylsping.xadfree;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Stable target-code identity for the installed X package.
 *
 * <p>versionCode/versionName are retained for logs only. They are NOT part of
 * the cache key or any functional decision. The stable token is derived from
 * the package name, the installed APK set sizes (base + splits) and the
 * signing certificate hash; it changes when the installed target code changes
 * and survives process restarts and randomized install paths.
 */
final class XTargetIdentity {
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
                    .getPackageInfo(targetPackage, 0);
            versionCode = packageInfo.getLongVersionCode();
            versionName = packageInfo.versionName;
            signingHash = sha256(signingCertificateBytes(packageInfo));
        } catch (Throwable ignored) {
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
                stableToken(targetPackage, size, splitSizes, signingHash),
                versionCode,
                versionName);
    }

    /** Deterministic plain-text token so JVM tests can reproduce it exactly. */
    static String stableToken(String packageName, long apkSize, long[] splitSizes,
                              String signingHash) {
        StringBuilder sb = new StringBuilder(packageName).append('|').append(apkSize);
        if (splitSizes != null) {
            for (long splitSize : splitSizes) {
                sb.append('|').append(splitSize);
            }
        }
        sb.append('|').append(signingHash == null ? "" : signingHash);
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

    private static byte[] signingCertificateBytes(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return new byte[0];
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo signingInfo = packageInfo.signingInfo;
                if (signingInfo != null && signingInfo.getApkContentsSigners() != null
                        && signingInfo.getApkContentsSigners().length > 0) {
                    return signingInfo.getApkContentsSigners()[0].toByteArray();
                }
            }
            if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                Signature signature = packageInfo.signatures[0];
                return signature == null ? new byte[0] : signature.toByteArray();
            }
        } catch (Throwable ignored) {
        }
        return new byte[0];
    }

    private static String sha256(byte[] value) {
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
