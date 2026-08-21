package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * State-machine integration tests (P1-9): the coordinator runs on its real
 * worker thread against a fake hook framework, manual scheduler and file
 * cache; only the DexKit query layer is substituted.
 */
public final class HookCoordinatorTest {
    /** JVM stand-in for the package-private Application.attach(Context). */
    public static final class AttachPoint {
        public Object attach(Object context) {
            return null;
        }
    }

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private FakeHookFramework framework;
    private FakeScheduler scheduler;
    private final AtomicLong clock = new AtomicLong();
    private File cacheDir;
    private XTargetIdentity identity;
    private ModuleLog log;

    @Before
    public void setUp() throws Exception {
        framework = new FakeHookFramework();
        scheduler = new FakeScheduler();
        cacheDir = temporaryFolder.newFolder("cache");
        identity = new XTargetIdentity("com.twitter.android", "/p", 100L, "cert",
                XTargetIdentity.stableToken("com.twitter.android", 100L, null, "cert", 12L),
                12L, "12.17.0-test");
        log = ModuleLog.silent();
    }

    /** Builds a coordinator with fixture components; resolver is overridable. */
    private CoordinatorBuilder builder() {
        return new CoordinatorBuilder();
    }

    final class CoordinatorBuilder {
        ResolverOverride override;
        XResolutionCache cache;
        final List<XDexKitSession> dexKitSessions =
                Collections.synchronizedList(new ArrayList<>());

        CoordinatorBuilder withResolver(ResolverOverride override) {
            this.override = override;
            return this;
        }

        CoordinatorBuilder withCache(XResolutionCache cache) {
            this.cache = cache;
            return this;
        }

        HookCoordinator build() {
            final XResolutionCache effectiveCache =
                    cache != null ? cache : new XResolutionCache(cacheDir);
            final ResolverOverride effectiveOverride = override;
            final List<XDexKitSession> sessions = dexKitSessions;
            return new HookCoordinator(log, "com.twitter.android",
                    HookCoordinatorTest.class.getClassLoader(),
                    framework, scheduler, clock::get, null,
                    new HookCoordinator.SessionComponents() {
                        @Override
                        public XTargetIdentity createIdentity(Object appContext) {
                            return identity;
                        }

                        @Override
                        public XResolutionCache createCache(Object appContext) {
                            return effectiveCache;
                        }

                        @Override
                        public XDexKitSession createDexKitSession(Object appContext) {
                            XDexKitSession session = new XDexKitSession(log, null,
                                    HookCoordinatorTest.class.getClassLoader(), null);
                            sessions.add(session);
                            return session;
                        }
                    }) {
                @Override
                java.util.concurrent.ExecutorService createWorker() {
                    // Short keepAlive so idle exit is observable in tests.
                    return new java.util.concurrent.ThreadPoolExecutor(
                            0, 1, 200L, java.util.concurrent.TimeUnit.MILLISECONDS,
                            new java.util.concurrent.LinkedBlockingQueue<>(),
                            runnable -> {
                                Thread thread = new Thread(runnable,
                                        "xadfree-resolver-worker-test");
                                thread.setDaemon(true);
                                return thread;
                            });
                }

                @Override
                HookCoordinator.ResolveOutcome resolveWithDexKit(
                        Class<?> objectType, Class<?> continuationType) {
                    if (effectiveOverride != null) {
                        return effectiveOverride.resolve(
                                this, objectType, continuationType);
                    }
                    return super.resolveWithDexKit(objectType, continuationType);
                }

                @Override
                Method attachObserverMethod() throws NoSuchMethodException {
                    // Application.attach is missing from the JVM android.jar.
                    return AttachPoint.class.getDeclaredMethod("attach", Object.class);
                }

                @Override
                Class<?> continuationTypeForSession() {
                    // Kotlin is not on the JVM test classpath; the structural
                    // fixture plays the same role for shape verification.
                    return WitnessFixtures.FakeContinuation.class;
                }
            };
        }
    }

    interface ResolverOverride {
        HookCoordinator.ResolveOutcome resolve(
                HookCoordinator coordinator, Class<?> objectType, Class<?> continuationType);
    }

    private static Method emitOf(Class<?> owner) {
        try {
            return owner.getDeclaredMethod("emit",
                    Object.class, WitnessFixtures.FakeContinuation.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static String descriptorOf(Method method) {
        return UrtEmitResolver.dexDescriptorOf(method);
    }

    private static ResolvedTarget emitTarget(Method method, boolean witnessed) {
        return new ResolvedTarget(XTargetResolver.KEY_URT_EMIT,
                XTargetResolver.TIER_STRONG,
                DescriptorUtils.classDescriptorOf(method.getDeclaringClass()),
                descriptorOf(method), 95, witnessed);
    }

    /** Fires the attach observer installed by {@code install()}. */
    private void fireAttach(Object context) {
        FakeHookFramework.Install attach = framework.findById("xadfree-application-attach");
        assertNotNull("attach observer not installed", attach);
        attach.fire(context);
    }

    private static List<CandidateScoring.ScoredCandidate> ambiguousCandidates() {
        return Arrays.asList(
                new CandidateScoring.ScoredCandidate(
                        DescriptorUtils.classDescriptorOf(WitnessFixtures.GoodEmit.class),
                        descriptorOf(emitOf(WitnessFixtures.GoodEmit.class)), 55, "strings:one"),
                new CandidateScoring.ScoredCandidate(
                        DescriptorUtils.classDescriptorOf(WitnessFixtures.RivalEmit.class),
                        descriptorOf(emitOf(WitnessFixtures.RivalEmit.class)), 52, "strings:one"));
    }

    // ------------------------------------------------------------------
    // Bootstrap / watchdog
    // ------------------------------------------------------------------

    @Test
    public void bootstrapDeadlineBeforeAttachDegradesFailOpen() throws Exception {
        HookCoordinator coordinator = builder().build();

        coordinator.install();
        assertEquals(HookCoordinator.State.ATTACH_WAIT, coordinator.stateForTests());

        scheduler.fire("bootstrap-deadline");
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.DEGRADED, coordinator.stateForTests());
        assertTrue(coordinator.terminalForTests());
        // The attach observer is released on degrade.
        assertTrue(framework.findById("xadfree-application-attach") == null);
        for (FakeHookFramework.Install install : framework.installs()) {
            assertTrue("install " + install.id + " still hooked", install.isUnhooked());
        }
    }

    // ------------------------------------------------------------------
    // Cache path
    // ------------------------------------------------------------------

    @Test
    public void cacheHitInstallsHookAndReachesReady() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), false));
        cache.saveTargets(identity, targets);

        HookCoordinator coordinator = builder().withCache(cache).build();
        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
        assertNotNull(framework.findById("xadfree-urt-emit-filter"));

        // Inline witness passes on the first real sample → cache gains
        // runtimeWitnessed=true (disk work happens on the worker, P2-2).
        FakeHookFramework.Install hook = framework.findById("xadfree-urt-emit-filter");
        hook.fire(WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")),
                new Object());
        coordinator.awaitWorkerIdleForTests();

        Map<String, ResolvedTarget> stored = cache.loadTargets(identity);
        assertTrue(stored.get(XTargetResolver.KEY_URT_EMIT).runtimeWitnessed);
    }

    @Test
    public void invalidCachedEmitTargetIsDroppedAndDegrades() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        // Correct shape but no interface override → INVALID.
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.NoOverrideEmit.class), false));
        cache.saveTargets(identity, targets);

        HookCoordinator coordinator = builder().withCache(cache).build();
        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        // Cache target dropped; DexKit unavailable in JVM → DEGRADED fail-open.
        assertEquals(HookCoordinator.State.DEGRADED, coordinator.stateForTests());
        assertTrue(cache.loadTargets(identity).isEmpty());
        assertNull(framework.findById("xadfree-urt-emit-filter"));
    }

    @Test
    public void cachedAdHelperIsVerifiedLikeFreshResolution() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), true));
        // Descriptor that no longer resolves to a method → must be rejected
        // and dropped, exactly like a fresh resolution failure (P0-3).
        targets.put(XTargetResolver.KEY_MODEL_AD_HELPER, new ResolvedTarget(
                XTargetResolver.KEY_MODEL_AD_HELPER, XTargetResolver.TIER_STRONG,
                DescriptorUtils.classDescriptorOf(HookCoordinatorTest.class),
                "Lio/github/yylsping/xadfree/HookCoordinatorTest;->missingHelper("
                        + "Lcom/x/models/timelines/items/UrtTimelineItem;)Z",
                0, false));
        cache.saveTargets(identity, targets);

        HookCoordinator coordinator = builder().withCache(cache).build();
        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
        Map<String, ResolvedTarget> stored = cache.loadTargets(identity);
        assertFalse("stale helper must be dropped",
                stored.containsKey(XTargetResolver.KEY_MODEL_AD_HELPER));
        assertTrue(stored.containsKey(XTargetResolver.KEY_URT_EMIT));
    }

    // ------------------------------------------------------------------
    // Witness path (P0-1 / P0-2)
    // ------------------------------------------------------------------

    @Test
    public void ambiguousCandidatesWaitForWitnessInsteadOfDegrading() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        final List<CandidateScoring.ScoredCandidate> ranked = ambiguousCandidates();
        HookCoordinator coordinator = builder()
                .withCache(cache)
                .withResolver((c, objectType, continuationType) ->
                        c.startWitnessProbes(ranked, new LinkedHashMap<>(),
                                objectType, continuationType))
                .build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        // P0-1: the session stays alive; no DEGRADED, no finishSession.
        assertEquals(HookCoordinator.State.WAITING_WITNESS, coordinator.stateForTests());
        assertFalse(coordinator.terminalForTests());
        // P0-2: the bootstrap watchdog is cancelled once probes start.
        assertFalse(scheduler.isScheduled("bootstrap-deadline"));
        assertNotNull(coordinator.activeWitnessForTests());
        assertEquals(2, countLiveProbes());
    }

    private int countLiveProbes() {
        int live = 0;
        for (FakeHookFramework.Install install : framework.installs()) {
            if ("xadfree-urt-emit-probe".equals(install.id) && !install.isUnhooked()) {
                live++;
            }
        }
        return live;
    }

    @Test
    public void bootstrapDeadlineDoesNotKillWaitingWitness() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        final List<CandidateScoring.ScoredCandidate> ranked = ambiguousCandidates();
        HookCoordinator coordinator = builder()
                .withCache(cache)
                .withResolver((c, objectType, continuationType) ->
                        c.startWitnessProbes(ranked, new LinkedHashMap<>(),
                                objectType, continuationType))
                .build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        // Simulate a stray bootstrap deadline while probes are active.
        coordinator.fireBootstrapDeadlineForTests();
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.WAITING_WITNESS, coordinator.stateForTests());
        assertTrue(scheduler.isScheduled("witness-timeout"));
    }

    @Test
    public void witnessPromotionInstallsHookAndPersistsRuntimeWitnessedTarget()
            throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        final List<CandidateScoring.ScoredCandidate> ranked = ambiguousCandidates();
        HookCoordinator coordinator = builder()
                .withCache(cache)
                .withResolver((c, objectType, continuationType) ->
                        c.startWitnessProbes(ranked, new LinkedHashMap<>(),
                                objectType, continuationType))
                .build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();
        RuntimeWitness witness = coordinator.activeWitnessForTests();
        assertNotNull(witness);
        String descriptor = descriptorOf(emitOf(WitnessFixtures.GoodEmit.class));

        // Two shaped invocations confirm the candidate (P1-8).
        witness.observe(descriptor,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")));
        witness.observe(descriptor,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-2")));
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
        // Probes cleaned, permanent filter installed.
        assertEquals(0, countLiveProbes());
        FakeHookFramework.Install filter = framework.findById("xadfree-urt-emit-filter");
        assertNotNull(filter);
        // The promoted target is persisted as runtime-witnessed.
        Map<String, ResolvedTarget> stored = cache.loadTargets(identity);
        ResolvedTarget emit = stored.get(XTargetResolver.KEY_URT_EMIT);
        assertNotNull(emit);
        assertTrue(emit.runtimeWitnessed);
        assertEquals(XTargetResolver.TIER_RUNTIME_WITNESS, emit.tier);
    }

    @Test
    public void witnessTimeoutDegradesFailOpenAndCleansProbes() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        final List<CandidateScoring.ScoredCandidate> ranked = ambiguousCandidates();
        HookCoordinator coordinator = builder()
                .withCache(cache)
                .withResolver((c, objectType, continuationType) ->
                        c.startWitnessProbes(ranked, new LinkedHashMap<>(),
                                objectType, continuationType))
                .build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        scheduler.fire("witness-timeout");
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.DEGRADED, coordinator.stateForTests());
        assertEquals(0, countLiveProbes());
        assertNull(framework.findById("xadfree-urt-emit-filter"));
    }

    @Test
    public void promotionPreservesModelTargetsInCache() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        final List<CandidateScoring.ScoredCandidate> ranked = ambiguousCandidates();
        // The resolver had resolved model targets before starting probes.
        final Map<String, ResolvedTarget> models = new LinkedHashMap<>();
        models.put(XTargetResolver.KEY_MODEL_AD_HELPER, new ResolvedTarget(
                XTargetResolver.KEY_MODEL_AD_HELPER, XTargetResolver.TIER_STRONG,
                "Lcom/x/models/timelines/items/l;",
                "Lcom/x/models/timelines/items/l;->a(Lcom/x/models/timelines/items/UrtTimelineItem;)Z",
                0, false));
        HookCoordinator coordinator = builder()
                .withCache(cache)
                .withResolver((c, objectType, continuationType) ->
                        c.startWitnessProbes(ranked, models, objectType, continuationType))
                .build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();
        RuntimeWitness witness = coordinator.activeWitnessForTests();
        String descriptor = descriptorOf(emitOf(WitnessFixtures.GoodEmit.class));
        witness.observe(descriptor,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")));
        witness.observe(descriptor,
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-2")));
        coordinator.awaitWorkerIdleForTests();

        // P1-7: promotion merges; the helper is still cached for warm starts.
        Map<String, ResolvedTarget> stored = cache.loadTargets(identity);
        assertTrue(stored.containsKey(XTargetResolver.KEY_MODEL_AD_HELPER));
        assertTrue(stored.containsKey(XTargetResolver.KEY_URT_EMIT));
    }

    // ------------------------------------------------------------------
    // Terminal freeze (P1-5)
    // ------------------------------------------------------------------

    @Test
    public void oldTimerAfterReadyCannotChangeState() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), true));
        cache.saveTargets(identity, targets);
        HookCoordinator coordinator = builder().withCache(cache).build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();
        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());

        // A stale deadline timer fires long after READY.
        scheduler.fire("bootstrap-deadline");
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
    }

    @Test
    public void lateWitnessEventAfterDegradedIsIgnored() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        final List<CandidateScoring.ScoredCandidate> ranked = ambiguousCandidates();
        HookCoordinator coordinator = builder()
                .withCache(cache)
                .withResolver((c, objectType, continuationType) ->
                        c.startWitnessProbes(ranked, new LinkedHashMap<>(),
                                objectType, continuationType))
                .build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();
        RuntimeWitness witness = coordinator.activeWitnessForTests();
        scheduler.fire("witness-timeout");
        coordinator.awaitWorkerIdleForTests();
        assertEquals(HookCoordinator.State.DEGRADED, coordinator.stateForTests());

        // Late observe on a settled witness: no promotion can happen.
        witness.observe(descriptorOf(emitOf(WitnessFixtures.GoodEmit.class)),
                WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")));
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.DEGRADED, coordinator.stateForTests());
        assertNull(framework.findById("xadfree-urt-emit-filter"));
    }

    @Test
    public void witnessFailureAfterReadySafetyDemotes() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), false));
        cache.saveTargets(identity, targets);
        HookCoordinator coordinator = builder().withCache(cache).build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();
        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());

        // Three non-List strikes kill the hook (P1-3 true unhook) and demote.
        FakeHookFramework.Install hook = framework.findById("xadfree-urt-emit-filter");
        for (int i = 0; i < WitnessLogic.FAILURE_LIMIT; i++) {
            hook.fire("not-a-list", new Object());
        }
        coordinator.awaitWorkerIdleForTests();

        assertTrue(hook.isUnhooked());
        assertEquals(HookCoordinator.State.DEGRADED, coordinator.stateForTests());
        // The invalidated target is gone from the cache.
        assertTrue(cache.loadTargets(identity).isEmpty());
    }

    @Test
    public void exactlyOnceTerminalTransition() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), true));
        cache.saveTargets(identity, targets);
        HookCoordinator coordinator = builder().withCache(cache).build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();
        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());

        // Stale deadline events after terminal must not flip READY.
        coordinator.fireBootstrapDeadlineForTests();
        coordinator.fireBootstrapDeadlineForTests();
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
    }

    // ------------------------------------------------------------------
    // 2.0.2 lifecycle: idle worker exit, timers, bridge release
    // ------------------------------------------------------------------

    @Test
    public void readyCancelsBootstrapTimer() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), true));
        cache.saveTargets(identity, targets);
        HookCoordinator coordinator = builder().withCache(cache).build();

        coordinator.install();
        assertTrue(scheduler.isScheduled("bootstrap-deadline"));
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
        assertFalse("one-shot bootstrap timer must not survive READY",
                scheduler.isScheduled("bootstrap-deadline"));
    }

    @Test
    public void workerThreadExitsAfterIdleAndRestartsForLaterEvents() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), false));
        cache.saveTargets(identity, targets);
        HookCoordinator coordinator = builder().withCache(cache).build();

        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();
        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());

        // Run the inline runtime self-check so no further events are pending,
        // then let the short keepAlive expire.
        FakeHookFramework.Install hook = framework.findById("xadfree-urt-emit-filter");
        assertNotNull(hook);
        hook.fire(WitnessFixtures.timeline(new WitnessFixtures.EntryWithId("tweet-1")),
                new Object());
        coordinator.awaitWorkerIdleForTests();
        waitUntilWorkerPoolSize(coordinator, 0);

        // Filtering continues synchronously — the hook does not need the worker.
        FakeHookFramework.Chain chain = hook.fire(
                WitnessFixtures.timeline(new WitnessFixtures.PromotedEntry()), new Object());
        assertEquals(1, chain.replacements.size());
        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());

        // A later legal control event transparently recreates the serial worker.
        coordinator.fireBootstrapDeadlineForTests();
        coordinator.awaitWorkerIdleForTests();
        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
        waitUntilWorkerPoolSize(coordinator, 0);
    }

    @Test
    public void dexKitBridgeIsReleasedAfterFailedResolve() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.NoOverrideEmit.class), false));
        cache.saveTargets(identity, targets);
        CoordinatorBuilder localBuilder = builder().withCache(cache);

        HookCoordinator coordinator = localBuilder.build();
        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.DEGRADED, coordinator.stateForTests());
        assertEquals("cache miss must still create and release the bridge session",
                1, localBuilder.dexKitSessions.size());
        assertTrue("bridge must be closed after the resolve attempt",
                localBuilder.dexKitSessions.get(0).isBridgeClosedForTests());
    }

    @Test
    public void warmCacheHitNeverCreatesADexKitSession() throws Exception {
        XResolutionCache cache = new XResolutionCache(cacheDir);
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(XTargetResolver.KEY_URT_EMIT,
                emitTarget(emitOf(WitnessFixtures.GoodEmit.class), true));
        cache.saveTargets(identity, targets);
        CoordinatorBuilder localBuilder = builder().withCache(cache);

        HookCoordinator coordinator = localBuilder.build();
        coordinator.install();
        fireAttach(new Object());
        coordinator.awaitWorkerIdleForTests();

        assertEquals(HookCoordinator.State.READY, coordinator.stateForTests());
        assertTrue("cache hit must not create a DexKit session",
                localBuilder.dexKitSessions.isEmpty());
    }

    private static void waitUntilWorkerPoolSize(HookCoordinator coordinator, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (coordinator.workerPoolSizeForTests() != expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("worker pool size", expected, coordinator.workerPoolSizeForTests());
    }
}

