package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public final class XTargetVerifierTest {
    /** Stand-in for kotlin.coroutines.Continuation on the JVM. */
    public interface FakeContinuation {
    }

    /** Stand-in for kotlinx.coroutines.flow.h (FlowCollector). */
    public interface FakeFlowCollector {
    }

    public static final class GoodEmit implements FakeFlowCollector {
        public Object emit(Object value, FakeContinuation continuation) {
            return null;
        }
    }

    public static final class StaticEmit implements FakeFlowCollector {
        public static Object emit(Object value, FakeContinuation continuation) {
            return null;
        }
    }

    public abstract static class AbstractEmit implements FakeFlowCollector {
        public abstract Object emit(Object value, FakeContinuation continuation);
    }

    public static final class WrongParams implements FakeFlowCollector {
        public Object emit(Object value, Object other) {
            return null;
        }
    }

    public static final class WrongReturn implements FakeFlowCollector {
        public void emit(Object value, FakeContinuation continuation) {
        }
    }

    public static final class NoFlowCollector {
        public Object emit(Object value, FakeContinuation continuation) {
            return null;
        }
    }

    public interface ModelLike {
        String getEntryId();

        long getSortIndex();
    }

    public static final class NotAnInterface {
    }

    private static ResolvedTarget targetFor(Class<?> owner) {
        for (Method method : owner.getDeclaredMethods()) {
            if ("emit".equals(method.getName())) {
                return new ResolvedTarget(XTargetResolver.KEY_URT_EMIT,
                        XTargetResolver.TIER_STRONG,
                        DescriptorUtils.classDescriptorOf(owner),
                        UrtEmitResolver.dexDescriptorOf(method), 90, false);
            }
        }
        throw new AssertionError("no emit method on " + owner);
    }

    private XTargetVerifier.Verification verify(Class<?> owner) {
        ClassLoader loader = owner.getClassLoader();
        return XTargetVerifier.verifyUrtEmit(targetFor(owner), loader,
                Object.class, FakeContinuation.class);
    }

    @Test
    public void goodShapeNeedsWitnessWhenNotYetWitnessed() throws Exception {
        XTargetVerifier.Verification verification = verify(GoodEmit.class);

        assertEquals(XTargetVerifier.Verdict.NEEDS_RUNTIME_WITNESS, verification.verdict);
    }

    @Test
    public void goodShapeValidatedStaticWhenPreviouslyWitnessed() throws Exception {
        Method method = GoodEmit.class.getDeclaredMethod(
                "emit", Object.class, FakeContinuation.class);
        ResolvedTarget witnessed = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                DescriptorUtils.classDescriptorOf(GoodEmit.class),
                UrtEmitResolver.dexDescriptorOf(method), 90, true);

        XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                witnessed, GoodEmit.class.getClassLoader(),
                Object.class, FakeContinuation.class);

        assertEquals(XTargetVerifier.Verdict.VALIDATED_STATIC, verification.verdict);
    }

    @Test
    public void staticMethodIsInvalid() throws Exception {
        assertEquals(XTargetVerifier.Verdict.INVALID, verify(StaticEmit.class).verdict);
    }

    @Test
    public void abstractMethodIsInvalid() throws Exception {
        assertEquals(XTargetVerifier.Verdict.INVALID, verify(AbstractEmit.class).verdict);
    }

    @Test
    public void wrongParameterShapeIsInvalid() throws Exception {
        assertEquals(XTargetVerifier.Verdict.INVALID, verify(WrongParams.class).verdict);
    }

    @Test
    public void wrongReturnShapeIsInvalid() throws Exception {
        assertEquals(XTargetVerifier.Verdict.INVALID, verify(WrongReturn.class).verdict);
    }

    @Test
    public void nullTargetIsInvalid() {
        XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                null, getClass().getClassLoader(), Object.class, FakeContinuation.class);
        assertEquals(XTargetVerifier.Verdict.INVALID, verification.verdict);
    }

    @Test
    public void unloadableClassIsInvalid() {
        ResolvedTarget target = new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                "Lcom/does/not/Exist;", "Lcom/does/not/Exist;->emit(..)", 90, true);

        XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                target, getClass().getClassLoader(), Object.class, FakeContinuation.class);

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
    public void descriptorRoundTripFindsTheMethod() throws Exception {
        Method method = GoodEmit.class.getDeclaredMethod(
                "emit", Object.class, FakeContinuation.class);
        String descriptor = UrtEmitResolver.dexDescriptorOf(method);

        Method resolved = DescriptorUtils.methodForDescriptor(
                descriptor, GoodEmit.class.getClassLoader());

        assertNotNull(resolved);
        assertEquals(method, resolved);
        assertTrue(descriptor.startsWith("Lio/github/yylsping/xadfree/XTargetVerifierTest$GoodEmit;->emit("));
        assertTrue(descriptor.contains("Ljava/lang/Object;"));
        assertTrue(descriptor.endsWith(")Ljava/lang/Object;"));
    }
}
