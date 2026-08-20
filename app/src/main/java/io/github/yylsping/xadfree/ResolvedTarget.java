package io.github.yylsping.xadfree;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One persisted resolution record. Descriptors are DexKit descriptors:
 * class: Lcom/example/Foo;  method: Lcom/example/Foo;->bar(Ljava/lang/Object;)V
 */
final class ResolvedTarget {
    final String key;
    /** Which tier produced it: fingerprint_strong / fingerprint_weak / semantic_name / fallback_compat / cache. */
    final String tier;
    final String classDescriptor;
    final String methodDescriptor;
    final int score;
    /** True once a real invocation proved the business shape (runtime witness). */
    final boolean runtimeWitnessed;
    final long resolvedAt;

    ResolvedTarget(String key, String tier, String classDescriptor, String methodDescriptor,
                   int score, boolean runtimeWitnessed) {
        this(key, tier, classDescriptor, methodDescriptor, score, runtimeWitnessed,
                System.currentTimeMillis());
    }

    private ResolvedTarget(String key, String tier, String classDescriptor,
                           String methodDescriptor, int score, boolean runtimeWitnessed,
                           long resolvedAt) {
        this.key = key;
        this.tier = tier;
        this.classDescriptor = classDescriptor;
        this.methodDescriptor = methodDescriptor;
        this.score = score;
        this.runtimeWitnessed = runtimeWitnessed;
        this.resolvedAt = resolvedAt;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("key", key);
        json.put("tier", tier);
        json.put("class", String.valueOf(classDescriptor));
        json.put("method", String.valueOf(methodDescriptor));
        json.put("score", score);
        json.put("witnessed", runtimeWitnessed);
        json.put("at", resolvedAt);
        return json;
    }

    static ResolvedTarget fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new ResolvedTarget(
                json.optString("key", ""),
                json.optString("tier", "cache"),
                json.optString("class", ""),
                json.optString("method", ""),
                json.optInt("score", 0),
                json.optBoolean("witnessed", false),
                json.optLong("at", 0L));
    }

    /** Copy with a different indexed key (urt_emit#2 ...) for stable multi-target merges. */
    ResolvedTarget withKey(String newKey) {
        return new ResolvedTarget(newKey, tier, classDescriptor, methodDescriptor,
                score, runtimeWitnessed, resolvedAt);
    }

    ResolvedTarget witnessed() {
        return new ResolvedTarget(key, tier, classDescriptor, methodDescriptor,
                score, true, resolvedAt);
    }

    String describe() {
        return "target=" + key + " tier=" + tier
                + " class=" + classDescriptor + " method=" + methodDescriptor
                + " score=" + score + " runtimeWitness=" + runtimeWitnessed;
    }
}
