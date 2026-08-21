package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public final class RuntimeWitnessTest {
    private static final String GOOD_DESCRIPTOR =
            descriptorOf(WitnessFixtures.GoodEmit.class);
    private static final String RIVAL_DESCRIPTOR =
            descriptorOf(WitnessFixtures.RivalEmit.class);

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private FakeHookFramework framework;
    private FakeScheduler scheduler;
    private AdDetector detector;
    private RuntimeWitness.Listener listener;

    @Before
    public void setUp() {
        framework = new FakeHookFramework();
        scheduler = new FakeScheduler();
        detector = new AdDetector(null);
        listener = new RuntimeWitness.Listener() {
            @Override
            public void onWitnessPromoted(String methodDescriptor, String evidenceSummary) {
                events.add("promoted:" + methodDescriptor);
            }

            @Override
            public void onWitnessExpired(String reason) {
                events.add("expired:" + reason);
            }
        };
    }

    private static String descriptorOf(Class<?> owner) {
        try {
            Method method = owner.getDeclaredMethod("emit",
                    Object.class, WitnessFixtures.FakeContinuation.class);
            return UrtEmitResolver.dexDescriptorOf(method);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private RuntimeWitness installTwoProbes() throws Exception {
        RuntimeWitness witness = new RuntimeWitness(framework, ModuleLog.silent(), detector, listener, scheduler);
        Method good = WitnessFixtures.GoodEmit.class.getDeclaredMethod("emit",
                Object.class, WitnessFixtures.FakeContinuation.class);
        Method rival = WitnessFixtures.RivalEmit.class.getDeclaredMethod("emit",
                Object.class, WitnessFixtures.FakeContinuation.class);
        assertTrue(witness.installProbes(Arrays.asList(good, rival)));
        assertEquals(2, framework.installs().size());
        assertTrue(scheduler.isScheduled("witness-timeout"));
        return witness;
    }

    @Test
    public void singleInvocationDoesNotPromote() throws Exception {
        RuntimeWitness witness = installTwoProbes();

        witness.observe(GOOD_DESCRIPTOR,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")));

        assertTrue(events.isEmpty());
        assertFalse(witness.isSettled());
    }

    @Test
    public void twoShapedInvocationsPromoteExactlyOnce() throws Exception {
        RuntimeWitness witness = installTwoProbes();

        witness.observe(GOOD_DESCRIPTOR,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")));
        witness.observe(GOOD_DESCRIPTOR,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-2")));

        assertEquals(Collections.singletonList("promoted:" + GOOD_DESCRIPTOR), events);
        assertTrue(witness.isSettled());
        assertEquals("promoted", witness.settleReason());
        // Every probe is unhooked; the timeout timer is gone.
        for (FakeHookFramework.Install install : framework.installs()) {
            assertTrue(install.isUnhooked());
        }
        assertFalse(scheduler.isScheduled("witness-timeout"));
    }

    @Test
    public void weakRatioDoesNotPromote() throws Exception {
        RuntimeWitness witness = installTwoProbes();
        List<Object> mixed = WitnessFixtures.timeline(
                "plain", "plain", "plain", new WitnessFixtures.EntryWithId("tweet-1"));

        witness.observe(GOOD_DESCRIPTOR, mixed);
        witness.observe(GOOD_DESCRIPTOR, mixed);
        witness.observe(RIVAL_DESCRIPTOR, mixed);

        assertTrue(events.isEmpty());
        assertFalse(witness.isSettled());
    }

    @Test
    public void strikesDropProbeAndRejectAllEndsFailOpen() throws Exception {
        RuntimeWitness witness = installTwoProbes();

        for (int i = 0; i < WitnessLogic.FAILURE_LIMIT; i++) {
            witness.observe(GOOD_DESCRIPTOR, "not-a-list");
        }

        // The struck probe is unhooked; the rival keeps observing.
        FakeHookFramework.Install good = framework.installs().get(0);
        assertTrue(good.isUnhooked());
        assertFalse(witness.isSettled());

        for (int i = 0; i < WitnessLogic.FAILURE_LIMIT; i++) {
            witness.observe(RIVAL_DESCRIPTOR, Arrays.asList("unshaped"));
        }

        assertEquals(Collections.singletonList("expired:allProbesRejected"), events);
        assertTrue(witness.isSettled());
    }

    @Test
    public void timeoutExpiresFailOpenAndUnhooks() throws Exception {
        RuntimeWitness witness = installTwoProbes();

        scheduler.fire("witness-timeout");

        assertEquals(Collections.singletonList("expired:timeout"), events);
        assertTrue(witness.isSettled());
        for (FakeHookFramework.Install install : framework.installs()) {
            assertTrue(install.isUnhooked());
        }
    }

    @Test
    public void cancelTearsDownWithoutPromoting() throws Exception {
        RuntimeWitness witness = installTwoProbes();

        witness.cancel();
        witness.observe(GOOD_DESCRIPTOR,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")));

        assertTrue(events.isEmpty());
        assertTrue(witness.isSettled());
        for (FakeHookFramework.Install install : framework.installs()) {
            assertTrue(install.isUnhooked());
        }
        assertFalse(scheduler.isScheduled("witness-timeout"));
    }

    @Test
    public void lateCallbacksAfterSettlementAreIgnored() throws Exception {
        RuntimeWitness witness = installTwoProbes();
        scheduler.fire("witness-timeout");

        witness.observe(GOOD_DESCRIPTOR,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")));
        witness.cancel();

        assertEquals(Collections.singletonList("expired:timeout"), events);
    }

    @Test
    public void concurrentCallbacksPromoteExactlyOnce() throws Exception {
        RuntimeWitness witness = installTwoProbes();
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                for (int i = 0; i < 20; i++) {
                    witness.observe(GOOD_DESCRIPTOR, WitnessFixtures.timeline(
                            new WitnessFixtures.EntryWithId("tweet-" + calls.incrementAndGet())));
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        // Exactly one promotion event regardless of the interleaving.
        long promotions = events.stream().filter(e -> e.startsWith("promoted:")).count();
        assertEquals(1, promotions);
        assertEquals("promoted", witness.settleReason());
        for (FakeHookFramework.Install install : framework.installs()) {
            assertTrue(install.isUnhooked());
        }
    }

    @Test
    public void emptyInstallListIsRejected() {
        RuntimeWitness witness = new RuntimeWitness(framework, ModuleLog.silent(), detector, listener, scheduler);

        assertFalse(witness.installProbes(Collections.emptyList()));
        assertTrue(events.isEmpty());
    }
}
