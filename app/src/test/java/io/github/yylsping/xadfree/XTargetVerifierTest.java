package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public final class XTargetVerifierTest {
    public interface ModelLike {
        String getEntryId();

        long getSortIndex();
    }

    public static final class NotAnInterface {
    }

    private static ResolvedTarget targetFor(Class<?> owner) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod("emit",
                Object.class, WitnessFixtures.FakeContinuation.class);
        return new ResolvedTarget(XTargetResolver.KEY_URT_EMIT,
                XTargetResolver.TIER_STRONG,
                DescriptorUtils.classDescriptorOf(owner),
                UrtEmitResolver.dexDescriptorOf(method), 90, false);
    }

    private XTargetVerifier.Verification verify(Class<?> owner) throws Exception {
        ClassLoader loader = owner.getClassLoader();
        return XTargetVerifier.verifyUrtEmit(targetFor(owner), loader,
                Object.class, WitnessFixtures.FakeContinuation.class);
    }

    @Test
    public void goodShapeNeedsWitnessWhenNotYetWitnessed() throws Exception {
        XTargetVerifier.Verification verification = verify(WitnessFixtures.GoodEmit.class);

        assertEquals(XTargetVerifier.Verdict.NEEDS_RUNTIME_WITNESS, verification.verdict);
    }

    @Test
    public void goodShapeValidatedStaticWhenPreviouslyWitnessed() throws Exception {
        Method method = WitnessFixtures.GoodEmit.class.getDeclaredMethod(
                "emit", Object.class, WitnessFixtures.FakeContinuation.class);
        ResolvedTarget witnessed = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                DescriptorUtils.classDescriptorOf(WitnessFixtures.GoodEmit.class),
                UrtEmitResolver.dexDescriptorOf(method), 90, true);

        XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                witnessed, WitnessFixtures.GoodEmit.class.getClassLoader(),
                Object.class, WitnessFixtures.FakeContinuation.class);

        assertEquals(XTargetVerifier.Verdict.VALIDATED_STATIC, verification.verdict);
    }

    @Test
    public void missingInterfaceOverrideIsInvalid() throws Exception {
        // P1-2: no hardcoded FlowCollector name — the override relation itself
        // is verified structurally, and its absence rejects the candidate.
        XTargetVerifier.Verification verification = verify(WitnessFixtures.NoOverrideEmit.class);

        assertEquals(XTargetVerifier.Verdict.INVALID, verification.verdict);
        assertTrue(verification.reason.contains("no interface override"));
    }

    @Test
    public void interfaceOverrideTriState() throws Exception {
        Method good = WitnessFixtures.GoodEmit.class.getDeclaredMethod(
                "emit", Object.class, WitnessFixtures.FakeContinuation.class);
        Method orphan = WitnessFixtures.NoOverrideEmit.class.getDeclaredMethod(
                "emit", Object.class, WitnessFixtures.FakeContinuation.class);

        assertEquals(XTargetVerifier.TriState.YES, XTargetVerifier.interfaceOverrideShape(good));
        assertEquals(XTargetVerifier.TriState.NO, XTargetVerifier.interfaceOverrideShape(orphan));
        assertEquals(XTargetVerifier.TriState.UNKNOWN, XTargetVerifier.interfaceOverrideShape(null));
    }

    @Test
    public void nullTargetIsInvalid() {
        XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                null, getClass().getClassLoader(), Object.class,
                WitnessFixtures.FakeContinuation.class);
        assertEquals(XTargetVerifier.Verdict.INVALID, verification.verdict);
    }

    @Test
    public void unloadableClassIsInvalid() {
        ResolvedTarget target = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                "Lcom/does/not/Exist;", "Lcom/does/not/Exist;->emit(..)", 90, true);

        XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                target, getClass().getClassLoader(), Object.class,
                WitnessFixtures.FakeContinuation.class);

        assertEquals(XTargetVerifier.Verdict.INVALID, verification.verdict);
    }

    @Test
    public void unloadableMethodIsInvalid() throws Exception {
        ResolvedTarget target = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                DescriptorUtils.classDescriptorOf(WitnessFixtures.GoodEmit.class),
                DescriptorUtils.classDescriptorOf(WitnessFixtures.GoodEmit.class)
                        + "->emit(Ljava/lang/String;)Ljava/lang/Object;",
                90, false);

        XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                target, WitnessFixtures.GoodEmit.class.getClassLoader(),
                Object.class, WitnessFixtures.FakeContinuation.class);

        assertEquals(XTargetVerifier.Verdict.INVALID, verification.verdict);
    }

    @Test
    public void modelInterfaceShapeCheck() {
        ResolvedTarget good = new ResolvedTarget(
                XTargetResolver.KEY_MODEL_URT_ITEM, XTargetResolver.TIER_SEMANTIC_NAME,
                DescriptorUtils.classDescriptorOf(ModelLike.class), "", 0, false);
        ResolvedTarget notInterface = new ResolvedTarget(
                XTargetResolver.KEY_MODEL_URT_ITEM, XTargetResolver.TIER_SEMANTIC_NAME,
                DescriptorUtils.classDescriptorOf(NotAnInterface.class), "", 0, false);

        assertEquals(null, XTargetVerifier.rejectModelInterface(
                good, ModelLike.class.getClassLoader()));
        assertTrue(XTargetVerifier.rejectModelInterface(
                notInterface, NotAnInterface.class.getClassLoader()) != null);
    }

    @Test
    public void adHelperRequiresModelInterface() throws Exception {
        Method helper = AdHelperHolder.class.getDeclaredMethod("isAd", Object.class);
        ResolvedTarget target = new ResolvedTarget(
                XTargetResolver.KEY_MODEL_AD_HELPER, XTargetResolver.TIER_STRONG,
                DescriptorUtils.classDescriptorOf(AdHelperHolder.class),
                UrtEmitResolver.dexDescriptorOf(helper), 0, false);

        assertEquals(null, XTargetVerifier.rejectAdHelper(
                target, AdHelperHolder.class.getClassLoader(), Object.class));
        // Without the model interface the helper cannot be shape-verified.
        assertTrue(XTargetVerifier.rejectAdHelper(
                target, AdHelperHolder.class.getClassLoader(), null) != null);
    }

    public static final class AdHelperHolder {
        public static boolean isAd(Object entry) {
            return false;
        }
    }

    @Test
    public void descriptorRoundTripFindsTheMethod() throws Exception {
        Method method = WitnessFixtures.GoodEmit.class.getDeclaredMethod(
                "emit", Object.class, WitnessFixtures.FakeContinuation.class);
        String descriptor = UrtEmitResolver.dexDescriptorOf(method);

        Method resolved = DescriptorUtils.methodForDescriptor(
                descriptor, WitnessFixtures.GoodEmit.class.getClassLoader());

        assertNotNull(resolved);
        assertEquals(method, resolved);
        assertTrue(descriptor.startsWith(
                "Lio/github/yylsping/xadfree/WitnessFixtures$GoodEmit;->emit("));
        assertTrue(descriptor.contains("Ljava/lang/Object;"));
        assertTrue(descriptor.endsWith(")Ljava/lang/Object;"));
    }
}
