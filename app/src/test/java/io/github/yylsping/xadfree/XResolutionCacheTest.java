package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class XResolutionCacheTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File filesDir;
    private XResolutionCache cache;
    private XTargetIdentity identityA;
    private XTargetIdentity identityB;

    @Before
    public void setUp() throws Exception {
        filesDir = temporaryFolder.newFolder("cache");
        cache = new XResolutionCache(filesDir, (temp, destination) -> {
            try (FileOutputStream out = new FileOutputStream(destination)) {
                Files.copy(temp.toPath(), out);
            }
            return true;
        });
        identityA = identity("com.twitter.android", 1000L, "cert-a");
        identityB = identity("com.twitter.android", 2000L, "cert-a");
    }

    private static XTargetIdentity identity(String packageName, long size, String cert) {
        return new XTargetIdentity(packageName, "/data/app/" + size + "/base.apk",
                size, cert, XTargetIdentity.stableToken(packageName, size, new long[]{7L}, cert),
                312170000L, "12.17.0-release.0");
    }

    private static Map<String, ResolvedTarget> sampleTargets() {
        Map<String, ResolvedTarget> map = new LinkedHashMap<>();
        map.put(XTargetResolver.KEY_URT_EMIT, new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                "Lcom/x/repositories/urt/h$c$a;",
                "Lcom/x/repositories/urt/h$c$a;->emit(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                90, true));
        map.put(XTargetResolver.KEY_MODEL_URT_ITEM, new ResolvedTarget(
                XTargetResolver.KEY_MODEL_URT_ITEM, XTargetResolver.TIER_SEMANTIC_NAME,
                "Lcom/x/models/timelines/items/UrtTimelineItem;", "", 0, false));
        return map;
    }

    @Test
    public void saveLoadRoundTrip() {
        cache.saveTargets(identityA, sampleTargets());

        Map<String, ResolvedTarget> loaded = cache.loadTargets(identityA);

        assertEquals(2, loaded.size());
        ResolvedTarget emit = loaded.get(XTargetResolver.KEY_URT_EMIT);
        assertNotNull(emit);
        assertEquals(XTargetResolver.TIER_STRONG, emit.tier);
        assertTrue(emit.runtimeWitnessed);
        assertEquals(90, emit.score);
        assertTrue(emit.methodDescriptor.contains("->emit("));
    }

    @Test
    public void differentIdentityIsAMiss() {
        cache.saveTargets(identityA, sampleTargets());

        assertTrue(cache.loadTargets(identityB).isEmpty());
        assertFalse(cache.hasIdentity(identityB));
        assertTrue(cache.hasIdentity(identityA));
    }

    @Test
    public void removeTargetDropsOnlyOneTarget() {
        cache.saveTargets(identityA, sampleTargets());

        cache.removeTarget(identityA, XTargetResolver.KEY_URT_EMIT);

        Map<String, ResolvedTarget> loaded = cache.loadTargets(identityA);
        assertEquals(1, loaded.size());
        assertTrue(loaded.containsKey(XTargetResolver.KEY_MODEL_URT_ITEM));
    }

    @Test
    public void removingLastTargetRemovesIdentity() {
        Map<String, ResolvedTarget> single = new LinkedHashMap<>();
        single.put(XTargetResolver.KEY_URT_EMIT, sampleTargets().get(XTargetResolver.KEY_URT_EMIT));
        cache.saveTargets(identityA, single);

        cache.removeTarget(identityA, XTargetResolver.KEY_URT_EMIT);

        assertFalse(cache.hasIdentity(identityA));
    }

    @Test
    public void unknownSchemaIsIgnored() throws Exception {
        File file = new File(filesDir, "xadfree_resolver_cache_v1.json");
        JSONObject foreign = new JSONObject();
        foreign.put("schema", 999);
        foreign.put("entries", new org.json.JSONArray());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(foreign.toString().getBytes(StandardCharsets.UTF_8));
        }

        assertFalse(cache.hasIdentity(identityA));

        // The next save replaces the foreign schema entirely.
        cache.saveTargets(identityA, sampleTargets());
        assertTrue(cache.hasIdentity(identityA));
    }

    @Test
    public void savesAreLruCapped() {
        for (int i = 0; i < XResolutionCache.MAX_ENTRIES + 2; i++) {
            cache.saveTargets(identity("com.twitter.android", 1000L + i, "cert-a"),
                    sampleTargets());
        }

        int identities = countIdentities();
        assertTrue("identities=" + identities, identities <= XResolutionCache.MAX_ENTRIES);
        // The most recent identity must survive.
        assertTrue(cache.hasIdentity(
                identity("com.twitter.android", 1000L + XResolutionCache.MAX_ENTRIES + 1, "cert-a")));
    }

    @Test
    public void removeIdentityLeavesOthers() {
        cache.saveTargets(identityA, sampleTargets());
        cache.saveTargets(identityB, sampleTargets());

        cache.removeIdentity(identityA);

        assertFalse(cache.hasIdentity(identityA));
        assertTrue(cache.hasIdentity(identityB));
    }

    private int countIdentities() {
        try {
            File file = new File(filesDir, "xadfree_resolver_cache_v1.json");
            if (!file.isFile()) {
                return 0;
            }
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            return root.getJSONArray("entries").length();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
