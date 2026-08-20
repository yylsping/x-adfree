package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class ResolvedTargetTest {
    @Test
    public void jsonRoundTrip() throws Exception {
        ResolvedTarget target = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                "Lcom/x/repositories/urt/h$c$a;",
                "Lcom/x/repositories/urt/h$c$a;->emit(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                95, true);

        ResolvedTarget decoded = ResolvedTarget.fromJson(target.toJson());

        assertEquals(target.key, decoded.key);
        assertEquals(target.tier, decoded.tier);
        assertEquals(target.classDescriptor, decoded.classDescriptor);
        assertEquals(target.methodDescriptor, decoded.methodDescriptor);
        assertEquals(target.score, decoded.score);
        assertEquals(target.runtimeWitnessed, decoded.runtimeWitnessed);
    }

    @Test
    public void nullJsonYieldsNull() {
        assertEquals(null, ResolvedTarget.fromJson(null));
    }

    @Test
    public void withKeyCopiesEverythingElse() {
        ResolvedTarget target = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_WEAK, "LA;", "LA;->a()", 55,
                false);

        ResolvedTarget rekeyed = target.withKey("urt_emit#2");

        assertEquals("urt_emit#2", rekeyed.key);
        assertEquals(target.methodDescriptor, rekeyed.methodDescriptor);
        assertEquals(target.tier, rekeyed.tier);
        assertEquals(target.score, rekeyed.score);
    }

    @Test
    public void witnessedFlagUpgrade() {
        ResolvedTarget target = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG, "LA;", "LA;->a()", 90,
                false);

        assertFalse(target.runtimeWitnessed);
        assertTrue(target.witnessed().runtimeWitnessed);
        assertEquals(target.methodDescriptor, target.witnessed().methodDescriptor);
    }
}
