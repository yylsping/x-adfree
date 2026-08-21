package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public final class UrtEmitHooksTest {
    private static final String AD_ID = "promoted-99";

    private FakeHookFramework framework;
    private AdDetector detector;
    private UrtListFilter filter;
    private List<String> events;
    private UrtEmitHooks.Listener listener;
    private UrtEmitHooks hooks;

    @Before
    public void setUp() {
        framework = new FakeHookFramework();
        detector = new AdDetector(null);
        detector.setModelInterface(WitnessFixtures.FakeUrtItem.class);
        filter = new UrtListFilter(detector);
        events = Collections.synchronizedList(new ArrayList<>());
        listener = new UrtEmitHooks.Listener() {
            @Override
            public void onWitnessPassed(String methodDescriptor) {
                events.add("passed:" + methodDescriptor);
            }

            @Override
            public void onWitnessFailed(String methodDescriptor, String reason) {
                events.add("failed:" + methodDescriptor + ":" + reason);
            }
        };
        hooks = new UrtEmitHooks(framework, new ModuleLog(null, false),
                detector, filter, listener);
    }

    private Method emitMethod() throws NoSuchMethodException {
        return WitnessFixtures.GoodEmit.class.getDeclaredMethod(
                "emit", Object.class, WitnessFixtures.FakeContinuation.class);
    }

    private FakeHookFramework.Install install() throws Exception {
        Method method = emitMethod();
        assertTrue(hooks.install(method));
        FakeHookFramework.Install install = framework.findByMethod(method);
        assertNotNull(install);
        return install;
    }

    private static String descriptorOf(Method method) {
        return UrtEmitResolver.dexDescriptorOf(method);
    }

    @Test
    public void installRegistersHookAndCounts() throws Exception {
        install();

        assertEquals(1, hooks.installedCount());
        assertTrue(hooks.hasInstalled());
        assertEquals(Collections.<String>emptyList(), events);
    }

    @Test
    public void doubleInstallOfSameDescriptorIsIdempotent() throws Exception {
        Method method = emitMethod();

        assertTrue(hooks.install(method));
        assertTrue(hooks.install(method));

        assertEquals(1, hooks.installedCount());
        assertEquals(1, framework.installs().size());
    }

    @Test
    public void witnessPassesOnFirstShapedSampleAndEnablesFiltering() throws Exception {
        FakeHookFramework.Install install = install();
        List<Object> timeline = WitnessFixtures.timeline(
                new WitnessFixtures.EntryWithId("tweet-1"),
                new WitnessFixtures.PromotedEntry(),
                new WitnessFixtures.EntryWithId("tweet-2"));

        // First invocation: witness passes on this very sample and the ad
        // it contains is already filtered (shape proven → safe to filter).
        FakeHookFramework.Chain first = install.fire(timeline, new Object());
        assertEquals(1, first.replacements.size());
        assertEquals(2, ((List<?>) first.replacements.get(0)[0]).size());
        assertEquals(Collections.singletonList("passed:" + descriptorOf(emitMethod())), events);

        // Second invocation: the ad is filtered and the replacement forwarded.
        FakeHookFramework.Chain second = install.fire(timeline, new Object());
        assertEquals(1, second.replacements.size());
        List<?> replaced = (List<?>) second.replacements.get(0)[0];
        assertEquals(2, replaced.size());
        assertFalse(replaced.contains(AD_ID));
    }

    @Test
    public void threeStrikesReallyUnhookThePermanentHook() throws Exception {
        FakeHookFramework.Install install = install();
        Method method = emitMethod();

        for (int i = 0; i < WitnessLogic.FAILURE_LIMIT; i++) {
            install.fire("not-a-list", new Object());
        }

        assertEquals(Collections.singletonList(
                "failed:" + descriptorOf(method) + ":argNotList"), events);
        // P1-3: the hook is truly unhooked and removed from the registry.
        assertTrue(install.isUnhooked());
        assertFalse(hooks.hasInstalled());
        assertEquals(0, hooks.installedCount());
        assertNullMethod(descriptorOf(method));
    }

    private void assertNullMethod(String descriptor) {
        assertEquals(null, hooks.methodOf(descriptor));
    }

    @Test
    public void emptyListsCarryNoEvidence() throws Exception {
        FakeHookFramework.Install install = install();
        Method method = emitMethod();

        for (int i = 0; i < 5; i++) {
            install.fire(new ArrayList<>(), new Object());
        }

        assertTrue(events.isEmpty());
        assertEquals(method, hooks.methodOf(descriptorOf(method)));
    }

    @Test
    public void filterExceptionFailsOpenToOriginal() throws Exception {
        FakeHookFramework.Install install = install();
        // Witness pass on a healthy list first.
        install.fire(WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")),
                new Object());

        // This list throws from get() during filtering.
        List<Object> hostile = new ArrayList<Object>() {
            @Override
            public Object get(int index) {
                if (index > 0) {
                    throw new IllegalStateException("hostile list");
                }
                return new WitnessFixtures.EntryWithId("tweet-1");
            }

            @Override
            public int size() {
                return 3;
            }
        };
        FakeHookFramework.Chain chain = install.fire(hostile, new Object());

        // Fail-open: original arguments forwarded, no replacement.
        assertEquals(0, chain.replacements.size());
    }

    @Test
    public void unknownVerdictsAreKept() throws Exception {
        FakeHookFramework.Install install = install();
        install.fire(WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")),
                new Object());

        Object unknown = new WitnessFixtures.FakeUrtItem() {
            @Override
            public String getEntryId() {
                throw new IllegalStateException("cannot read");
            }

            @Override
            public long getSortIndex() {
                return 0L;
            }
        };
        FakeHookFramework.Chain chain = install.fire(
                WitnessFixtures.timeline(unknown), new Object());

        // UNKNOWN is never removed; filtering may still run for other items.
        if (!chain.replacements.isEmpty()) {
            List<?> replaced = (List<?>) chain.replacements.get(0)[0];
            assertTrue(replaced.contains(unknown));
        }
    }

    @Test
    public void foreignListImplementationPassesThroughUnfiltered() throws Exception {
        FakeHookFramework.Install install = install();
        install.fire(WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")),
                new Object());

        // P2-3: Arrays$ArrayList is outside the replacement allowlist.
        List<Object> foreign = WitnessFixtures.foreignList(
                new WitnessFixtures.PromotedEntry(),
                new WitnessFixtures.EntryWithId("tweet-1"));
        FakeHookFramework.Chain chain = install.fire(foreign, new Object());

        assertEquals(0, chain.replacements.size());
    }

    @Test
    public void arrayListsAreReplacedAfterWitness() throws Exception {
        FakeHookFramework.Install install = install();
        install.fire(WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")),
                new Object());

        List<Object> timeline = new ArrayList<>(Arrays.asList(
                new WitnessFixtures.PromotedEntry(),
                new WitnessFixtures.EntryWithId("tweet-2")));
        FakeHookFramework.Chain chain = install.fire(timeline, new Object());

        assertEquals(1, chain.replacements.size());
        assertEquals(1, ((List<?>) chain.replacements.get(0)[0]).size());
    }

    @Test
    public void unhookRemovesRegistryEntry() throws Exception {
        Method method = emitMethod();
        hooks.install(method);
        String descriptor = descriptorOf(method);

        assertTrue(hooks.unhook(descriptor));
        assertFalse(hooks.unhook(descriptor)); // Idempotent second call.
        assertFalse(hooks.hasInstalled());
    }

    // ------------------------------------------------------------------
    // P1-2 (2.0.2): WitnessState concurrency
    // ------------------------------------------------------------------

    @Test
    public void concurrentShapedInvocationsNotifyPassedExactlyOnce() throws Exception {
        final FakeHookFramework.Install install = install();
        final List<Object> timeline = WitnessFixtures.timeline(
                new WitnessFixtures.EntryWithId("tweet-1"));
        int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                install.fire(new ArrayList<>(timeline), new Object());
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        long passedEvents = events.stream()
                .filter(e -> e.startsWith("passed:")).count();
        assertEquals("passed must be exactly-once under concurrency", 1, passedEvents);
        // After the race the hook is still installed and filters normally.
        assertTrue(hooks.hasInstalled());
        FakeHookFramework.Chain chain = install.fire(
                WitnessFixtures.timeline(new WitnessFixtures.PromotedEntry()), new Object());
        assertEquals(1, chain.replacements.size());
    }

    @Test
    public void concurrentStrikesFailAndUnhookExactlyOnce() throws Exception {
        final FakeHookFramework.Install install = install();
        int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                install.fire("not-a-list", new Object());
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        long failedEvents = events.stream()
                .filter(e -> e.startsWith("failed:")).count();
        assertEquals("failure must be exactly-once under concurrency", 1, failedEvents);
        assertTrue("hook must be truly unhooked once", install.isUnhooked());
        assertFalse(hooks.hasInstalled());
        assertEquals(0, hooks.installedCount());
    }

    @Test
    public void mixedPassAndFailureRaceKeepsExactlyOncePerKind() throws Exception {
        final FakeHookFramework.Install install = install();
        final List<Object> timeline = WitnessFixtures.timeline(
                new WitnessFixtures.EntryWithId("tweet-1"));
        int threads = 6;
        final CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final boolean shaped = t % 2 == 0;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                if (shaped) {
                    install.fire(new ArrayList<>(timeline), new Object());
                } else {
                    install.fire("not-a-list", new Object());
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        long passedEvents = events.stream()
                .filter(e -> e.startsWith("passed:")).count();
        long failedEvents = events.stream()
                .filter(e -> e.startsWith("failed:")).count();
        assertTrue("at most one pass notification", passedEvents <= 1);
        assertTrue("at most one failure notification", failedEvents <= 1);
        // Each kind fires at most once; a pass followed by repeated
        // argNotList strikes may still disarm the hook later (by design).
    }

    @Test
    public void deadStateLateCallbacksAreInert() throws Exception {
        FakeHookFramework.Install install = install();
        for (int i = 0; i < WitnessLogic.FAILURE_LIMIT; i++) {
            install.fire("not-a-list", new Object());
        }
        assertEquals(1, events.size());

        // Late invocations after dead: no further events, no filtering.
        FakeHookFramework.Chain late = install.fire(
                WitnessFixtures.timeline(new WitnessFixtures.PromotedEntry()), new Object());
        assertEquals(0, late.replacements.size());
        assertEquals("no duplicate notifications", 1, events.size());
        assertFalse(hooks.hasInstalled());
    }
}
