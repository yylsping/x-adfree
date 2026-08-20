package io.github.yylsping.xadfree;

import java.util.Map;

/** Target keys and descriptor-stable multi-target merging. */
final class XTargetResolver {
    /** Core data-layer hook: the DB-flow map/emit step of DefaultURTTimelineRepository. */
    static final String KEY_URT_EMIT = "urt_emit";
    /** Unobfuscated polymorphic model interface (witness support). */
    static final String KEY_MODEL_URT_ITEM = "model.urtItemInterface";
    /** The app's own isAd(UrtTimelineItem) static predicate, when resolvable. */
    static final String KEY_MODEL_AD_HELPER = "model.adHelperIsAd";

    static final String TIER_STRONG = "fingerprint_strong";
    static final String TIER_WEAK = "fingerprint_weak";
    static final String TIER_SEMANTIC_NAME = "semantic_name";
    static final String TIER_RUNTIME_WITNESS = "runtime_witness";
    static final String TIER_FALLBACK = "fallback_compat";

    private XTargetResolver() {
    }

    /** urt_emit entries may be suffixed (urt_emit#2 ...) for multi-method coverage. */
    static boolean isUrtEmitKey(String key) {
        return key != null
                && (key.equals(KEY_URT_EMIT) || key.startsWith(KEY_URT_EMIT + "#"));
    }

    static String indexedKey(String base, int index) {
        return index == 0 ? base : base + "#" + (index + 1);
    }

    /**
     * Merges one session's incoming targets into the live map with
     * descriptor-stable keys. Multi-target keys (urt_emit#N) are NOT
     * positional: a target keeps the key its descriptor already owns in the
     * live map, and only a genuinely new descriptor takes the next free
     * suffix. Candidate-set or order changes across sessions therefore cannot
     * re-key an existing descriptor and overwrite an unrelated entry.
     */
    static void mergeTargets(Map<String, ResolvedTarget> live,
                             Map<String, ResolvedTarget> incoming) {
        if (live == null || incoming == null) {
            return;
        }
        for (ResolvedTarget target : incoming.values()) {
            if (target == null || target.key == null || target.key.isEmpty()) {
                continue;
            }
            if (isUrtEmitKey(target.key)) {
                String key = stableKey(live, KEY_URT_EMIT, target.methodDescriptor);
                live.put(key, target.withKey(key));
            } else {
                // Fixed keys: the key itself is the identity.
                live.put(target.key, target);
            }
        }
    }

    private static String stableKey(Map<String, ResolvedTarget> live, String base,
                                    String descriptor) {
        if (descriptor != null && !descriptor.isEmpty()) {
            for (Map.Entry<String, ResolvedTarget> entry : live.entrySet()) {
                if (!sameFamily(entry.getKey(), base)) {
                    continue;
                }
                if (descriptor.equals(entry.getValue().methodDescriptor)) {
                    return entry.getKey();
                }
            }
        }
        for (int index = 0; ; index++) {
            String key = indexedKey(base, index);
            if (!live.containsKey(key)) {
                return key;
            }
        }
    }

    private static boolean sameFamily(String key, String base) {
        return key.equals(base) || key.startsWith(base + "#");
    }
}
