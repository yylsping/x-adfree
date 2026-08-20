package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class XTargetResolverTest {
    private static ResolvedTarget emitTarget(String descriptor) {
        return new ResolvedTarget(XTargetResolver.KEY_URT_EMIT,
                XTargetResolver.TIER_STRONG, "Lcom/x/repositories/urt/h$c$a;",
                descriptor, 90, false);
    }

    @Test
    public void firstEmitTargetTakesBaseKey() {
        Map<String, ResolvedTarget> live = new LinkedHashMap<>();

        XTargetResolver.mergeTargets(live, mapOf(emitTarget("LA;->emit(..)")));

        assertTrue(live.containsKey(XTargetResolver.KEY_URT_EMIT));
        assertEquals(1, live.size());
    }

    @Test
    public void distinctDescriptorsGetStableSuffixes() {
        Map<String, ResolvedTarget> live = new LinkedHashMap<>();
        XTargetResolver.mergeTargets(live, mapOf(emitTarget("LA;->a()")));
        XTargetResolver.mergeTargets(live, mapOf(emitTarget("LB;->b()")));

        assertEquals(2, live.size());
        assertTrue(live.containsKey("urt_emit#2"));
    }

    @Test
    public void sameDescriptorKeepsItsKeyAcrossReorderedSessions() {
        Map<String, ResolvedTarget> live = new LinkedHashMap<>();
        XTargetResolver.mergeTargets(live, mapOf(
                emitTarget("LA;->a()"), emitTarget("LB;->b()")));
        assertEquals("LA;->a()", live.get(XTargetResolver.KEY_URT_EMIT).methodDescriptor);
        assertEquals("LB;->b()", live.get("urt_emit#2").methodDescriptor);

        // Next session returns them in the OPPOSITE order: descriptors must
        // keep their keys; nothing may be overwritten or re-keyed.
        XTargetResolver.mergeTargets(live, mapOf(
                emitTarget("LB;->b()"), emitTarget("LA;->a()"), emitTarget("LC;->c()")));

        assertEquals("LA;->a()", live.get(XTargetResolver.KEY_URT_EMIT).methodDescriptor);
        assertEquals("LB;->b()", live.get("urt_emit#2").methodDescriptor);
        assertEquals("LC;->c()", live.get("urt_emit#3").methodDescriptor);
        assertEquals(3, live.size());
    }

    @Test
    public void fixedKeysAreIdentity() {
        Map<String, ResolvedTarget> live = new LinkedHashMap<>();
        ResolvedTarget model = new ResolvedTarget(XTargetResolver.KEY_MODEL_URT_ITEM,
                XTargetResolver.TIER_SEMANTIC_NAME, "Lcom/x/models/...", "", 0, false);

        XTargetResolver.mergeTargets(live, mapOf(model));

        assertTrue(live.containsKey(XTargetResolver.KEY_MODEL_URT_ITEM));
        assertFalse(XTargetResolver.isUrtEmitKey(XTargetResolver.KEY_MODEL_URT_ITEM));
    }

    @Test
    public void emitKeyFamilyDetection() {
        assertTrue(XTargetResolver.isUrtEmitKey("urt_emit"));
        assertTrue(XTargetResolver.isUrtEmitKey("urt_emit#2"));
        assertFalse(XTargetResolver.isUrtEmitKey("urt_emitfoo"));
        assertFalse(XTargetResolver.isUrtEmitKey(null));
    }

    @Test
    public void indexedKeys() {
        assertEquals("urt_emit", XTargetResolver.indexedKey("urt_emit", 0));
        assertEquals("urt_emit#2", XTargetResolver.indexedKey("urt_emit", 1));
        assertEquals("urt_emit#3", XTargetResolver.indexedKey("urt_emit", 2));
    }

    private static Map<String, ResolvedTarget> mapOf(ResolvedTarget... targets) {
        Map<String, ResolvedTarget> map = new LinkedHashMap<>();
        int index = 0;
        for (ResolvedTarget target : targets) {
            map.put(XTargetResolver.indexedKey(target.key, index++), target);
        }
        return map;
    }
}
