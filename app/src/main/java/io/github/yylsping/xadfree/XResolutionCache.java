package io.github.yylsping.xadfree;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-identity resolver cache.
 *
 * <p>Stores at most {@link #MAX_ENTRIES} identities; every write is atomic and
 * LRU-ordered by lastUsedAt. versionCode/versionName never participate in
 * cache identity or validity. One invalid target removes only that target of
 * the current identity — never the whole store.
 */
final class XResolutionCache {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_ENTRIES = 5;
    static final int MAX_TOTAL_BYTES = 256 * 1024;
    private static final String FILE_NAME = "xadfree_resolver_cache_v1.json";

    private final File file;
    private final CacheAtomicWriter.ReplaceOperation replaceOperation;
    private final Object lock = new Object();

    XResolutionCache(Context appContext) {
        this(appContext.getFilesDir(), CacheAtomicWriter.NIO_MOVE_REPLACE);
    }

    XResolutionCache(File filesDir) {
        this(filesDir, CacheAtomicWriter.NIO_MOVE_REPLACE);
    }

    XResolutionCache(File filesDir, CacheAtomicWriter.ReplaceOperation replaceOperation) {
        this.file = new File(filesDir, FILE_NAME);
        this.replaceOperation = replaceOperation;
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        if (temp.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    Map<String, ResolvedTarget> loadTargets(XTargetIdentity identity) {
        synchronized (lock) {
            JSONObject entry = findEntry(identity);
            if (entry == null) {
                return new LinkedHashMap<>();
            }
            return decodeTargets(entry);
        }
    }

    boolean hasIdentity(XTargetIdentity identity) {
        synchronized (lock) {
            return findEntry(identity) != null;
        }
    }

    void saveTargets(XTargetIdentity identity, Map<String, ResolvedTarget> targets) {
        synchronized (lock) {
            try {
                JSONObject root = readJson();
                if (root == null || root.optInt("schema", 0) != SCHEMA_VERSION) {
                    root = new JSONObject();
                    root.put("schema", SCHEMA_VERSION);
                    root.put("entries", new JSONArray());
                }
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) {
                    entries = new JSONArray();
                    root.put("entries", entries);
                }
                JSONObject replacement = encodeEntry(identity, targets, System.currentTimeMillis());
                JSONArray merged = new JSONArray();
                merged.put(replacement);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject candidate = entries.optJSONObject(i);
                    XTargetIdentity candidateIdentity = XTargetIdentity.fromJson(
                            candidate == null ? null : candidate.optJSONObject("identity"));
                    if (candidateIdentity != null
                            && identity.token.equals(candidateIdentity.token)) {
                        continue;
                    }
                    merged.put(candidate);
                }
                root.put("entries", merged);
                writeJson(root, identity.token);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Merges a single target into the identity's stored set (P1-7): existing
     * targets of the same identity — e.g. an already resolved adHelper — are
     * preserved, never overwritten by a partial promotion write. Keys follow
     * {@link XTargetResolver#mergeTargets} descriptor-stable rules.
     */
    void updateTarget(XTargetIdentity identity, ResolvedTarget target) {
        if (target == null) {
            return;
        }
        synchronized (lock) {
            try {
                Map<String, ResolvedTarget> current = loadTargetsLocked(identity);
                XTargetResolver.mergeTargets(current,
                        java.util.Collections.singletonMap(target.key, target));
                saveTargets(identity, current);
            } catch (Throwable ignored) {
            }
        }
    }

    private Map<String, ResolvedTarget> loadTargetsLocked(XTargetIdentity identity) {
        JSONObject entry = findEntry(identity);
        if (entry == null) {
            return new LinkedHashMap<>();
        }
        return decodeTargets(entry);
    }

    /** Removes a single target key of the current identity; the rest stay untouched. */
    void removeTarget(XTargetIdentity identity, String key) {
        synchronized (lock) {
            try {
                JSONObject entry = findEntry(identity);
                if (entry == null) {
                    return;
                }
                Map<String, ResolvedTarget> targets = decodeTargets(entry);
                if (targets.remove(key) == null) {
                    return;
                }
                if (targets.isEmpty()) {
                    removeIdentityLocked(identity);
                    return;
                }
                JSONObject replacement = encodeEntry(identity, targets,
                        entry.optLong("lastUsedAt", 0L));
                JSONObject root = readJson();
                if (root == null) {
                    return;
                }
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) {
                    return;
                }
                JSONArray merged = new JSONArray();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject candidate = entries.optJSONObject(i);
                    XTargetIdentity candidateIdentity = XTargetIdentity.fromJson(
                            candidate == null ? null : candidate.optJSONObject("identity"));
                    if (candidateIdentity != null
                            && identity.token.equals(candidateIdentity.token)) {
                        merged.put(replacement);
                        continue;
                    }
                    merged.put(candidate);
                }
                root.put("entries", merged);
                writeJson(root, identity.token);
            } catch (Throwable ignored) {
            }
        }
    }

    void removeIdentity(XTargetIdentity identity) {
        synchronized (lock) {
            removeIdentityLocked(identity);
        }
    }

    private void removeIdentityLocked(XTargetIdentity identity) {
        try {
            JSONObject root = readJson();
            if (root == null) {
                return;
            }
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) {
                return;
            }
            JSONArray kept = new JSONArray();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject candidate = entries.optJSONObject(i);
                XTargetIdentity candidateIdentity = XTargetIdentity.fromJson(
                        candidate == null ? null : candidate.optJSONObject("identity"));
                if (candidateIdentity != null && identity.token.equals(candidateIdentity.token)) {
                    continue;
                }
                kept.put(candidate);
            }
            root.put("entries", kept);
            writeJson(root, null);
        } catch (Throwable ignored) {
        }
    }

    private JSONObject findEntry(XTargetIdentity identity) {
        JSONObject root = readJson();
        if (root == null || root.optInt("schema", 0) != SCHEMA_VERSION) {
            return null;
        }
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) {
            return null;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject candidate = entries.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            XTargetIdentity candidateIdentity =
                    XTargetIdentity.fromJson(candidate.optJSONObject("identity"));
            if (candidateIdentity == null || !identity.sameTarget(candidateIdentity)) {
                continue;
            }
            touchLastUsed(candidate, root, identity);
            return candidate;
        }
        return null;
    }

    private void touchLastUsed(JSONObject entry, JSONObject root, XTargetIdentity identity) {
        try {
            long now = System.currentTimeMillis();
            if (entry.optLong("lastUsedAt", 0L) == now) {
                return;
            }
            entry.put("lastUsedAt", now);
            writeJson(root, identity.token);
        } catch (Throwable ignored) {
        }
    }

    private boolean writeJson(JSONObject root, String protectToken) {
        try {
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) {
                entries = new JSONArray();
                root.put("entries", entries);
            }
            entries = evictForCount(entries, protectToken);
            root.put("entries", entries);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TOTAL_BYTES) {
                // Resolver records are tiny; hitting the byte budget with so few
                // entries means the file is not ours. Refuse to loop.
                return false;
            }
            return CacheAtomicWriter.write(file, bytes, replaceOperation);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private JSONArray evictForCount(JSONArray entries, String protectToken) throws JSONException {
        if (entries.length() <= MAX_ENTRIES) {
            return entries;
        }
        List<long[]> order = new ArrayList<>(); // [index, lastUsedAt]
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            order.add(new long[]{i, entry == null ? 0L : entry.optLong("lastUsedAt", 0L)});
        }
        order.sort((a, b) -> Long.compare(a[1], b[1]));
        int toDrop = entries.length() - MAX_ENTRIES;
        List<Integer> dropIndexes = new ArrayList<>();
        for (int i = 0; i < order.size() && toDrop > 0; i++) {
            int index = (int) order.get(i)[0];
            JSONObject entry = entries.optJSONObject(index);
            XTargetIdentity entryIdentity = XTargetIdentity.fromJson(
                    entry == null ? null : entry.optJSONObject("identity"));
            if (entryIdentity != null && entryIdentity.token.equals(protectToken)) {
                continue;
            }
            dropIndexes.add(index);
            toDrop--;
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            if (!dropIndexes.contains(i)) {
                kept.put(entries.get(i));
            }
        }
        return kept;
    }

    private static JSONObject encodeEntry(XTargetIdentity identity,
                                          Map<String, ResolvedTarget> targets,
                                          long lastUsedAt) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("identity", identity.toJson());
        entry.put("lastUsedAt", lastUsedAt);
        JSONArray array = new JSONArray();
        for (ResolvedTarget target : targets.values()) {
            array.put(target.toJson());
        }
        entry.put("targets", array);
        return entry;
    }

    private static Map<String, ResolvedTarget> decodeTargets(JSONObject entry) {
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        JSONArray array = entry == null ? null : entry.optJSONArray("targets");
        if (array == null) {
            return targets;
        }
        for (int i = 0; i < array.length(); i++) {
            ResolvedTarget target = ResolvedTarget.fromJson(array.optJSONObject(i));
            if (target != null && target.key != null && !target.key.isEmpty()) {
                targets.put(target.key, target);
            }
        }
        return targets;
    }

    private JSONObject readJson() {
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), MAX_TOTAL_BYTES + 1L)];
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset == 0) {
                return null;
            }
            return new JSONObject(new String(bytes, 0, offset, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
