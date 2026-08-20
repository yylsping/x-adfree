package io.github.yylsping.xadfree;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DexKit-backed resolution of the URT data-layer emit target.
 *
 * <p>Discovery ladder (P1-1): every scoring feature is reachable through at
 * least one discovery entry — a candidate is only scoreable after some entry
 * surfaced it, so discovery and scoring features must stay aligned. Entries
 * run in selectivity order and the ladder stops as soon as one batch yields
 * an unambiguous above-threshold leader (keeps the 12.17.0 strong path fast);
 * on fingerprint drift the remaining entries keep the resolver alive.
 *
 * <p>Entry matrix (target urt_emit):
 * <pre>
 *   1 primary string     "Ad removal: "                      (+CPS shape)
 *   2 secondary string   " ads removed (spacing="
 *   3 spacing metric     "minimum_spacing_ad_removal"
 *   4 spacing logic      "minimum_spacing"
 *   5 brand safety       "brand_safety"
 *   6 structural         name "emit" + (Object,Continuation)->Object
 *                        within com.x.repositories
 *   7 legacy seed        12.3.1 historical class via reflection
 * </pre>
 * Every discovered candidate is re-scored on orthogonal features; nothing is
 * accepted blindly and no {@code .single()}/{@code get(0)} shortcut exists.
 */
final class UrtEmitResolver {
    /**
     * Historically observed seed from X 12.3.1-release.0: the FlowCollector
     * lambda of DefaultURTTimelineRepository carried emit(Object, Continuation)
     * under this name. Compatibility fallback only.
     */
    static final String LEGACY_EMIT_CLASS_12_3_1 = "com.x.repositories.urt.j$a";
    static final String MODEL_INTERFACE_NAME = "com.x.models.timelines.items.UrtTimelineItem";
    static final String PROMOTED_METADATA_NAME = "com.x.models.TimelinePromotedMetadata";
    /** Structural discovery corridor: business package name, not a hook. */
    static final String STRUCTURAL_SEARCH_PACKAGE = "com.x.repositories";

    private final DexKitBridge bridge;
    private final ClassLoader loader;
    private final ModuleLog log;
    private final Class<?> objectType;
    private final Class<?> continuationType;

    /** DexKit per-method string usage, captured while entry results are at hand. */
    private final Map<String, Set<String>> stringUsageByDescriptor = new LinkedHashMap<>();

    UrtEmitResolver(DexKitBridge bridge, ClassLoader loader, ModuleLog log,
                    Class<?> objectType, Class<?> continuationType) {
        this.bridge = bridge;
        this.loader = loader;
        this.log = log;
        this.objectType = objectType;
        this.continuationType = continuationType;
    }

    /** Ranked emit candidates, best first. May be empty (fail-open, no hook). */
    List<CandidateScoring.ScoredCandidate> resolveEmitCandidates() {
        Map<String, CandidateScoring.ScoredCandidate> merged = new LinkedHashMap<>();

        runStringEntry(merged, "strong", CandidateScoring.STRING_PRIMARY, true);
        List<CandidateScoring.ScoredCandidate> ranked = rank(merged, 1);
        for (int entry = 1; entry < CandidateScoring.DISCOVERY_STRINGS.length; entry++) {
            if (CandidateScoring.discoveryCanStop(ranked)) {
                log.info("resolver target=urt_emit discoveryEarlyStop=true afterEntry="
                        + entry + " score=" + ranked.get(0).score);
                break;
            }
            String value = CandidateScoring.DISCOVERY_STRINGS[entry];
            runStringEntry(merged, "string:" + value, value, false);
            ranked = rank(merged, entry + 1);
        }
        if (!CandidateScoring.discoveryCanStop(ranked)) {
            runStructuralEntry(merged);
            ranked = rank(merged, CandidateScoring.DISCOVERY_STRINGS.length + 1);
        }
        if (!CandidateScoring.discoveryCanStop(ranked)) {
            collectSeedTier(merged, queryLegacyFallback());
            ranked = rank(merged, CandidateScoring.DISCOVERY_STRINGS.length + 2);
        }

        if (ranked.isEmpty()) {
            log.info("resolver target=urt_emit tier=all failed=unresolved failOpen=true");
        }
        return ranked;
    }

    private List<CandidateScoring.ScoredCandidate> rank(
            Map<String, CandidateScoring.ScoredCandidate> merged, int entriesRun) {
        List<CandidateScoring.ScoredCandidate> ranked = new ArrayList<>(merged.values());
        ranked.sort((a, b) -> Integer.compare(b.score, a.score));
        if (!ranked.isEmpty()) {
            log.info("resolver target=urt_emit entriesRun=" + entriesRun
                    + " candidateCount=" + ranked.size()
                    + " topScore=" + ranked.get(0).score
                    + " ambiguous=" + CandidateScoring.isAmbiguousTop(ranked));
            for (CandidateScoring.ScoredCandidate candidate : ranked) {
                log.info("candidate target=urt_emit descriptor=" + candidate.methodDescriptor
                        + " score=" + candidate.score + " evidence=" + candidate.evidence);
            }
        }
        return ranked;
    }

    /**
     * The unobfuscated polymorphic model interface. Name-exact because these
     * are semantic public model names (kotlinx.serialization registry), not
     * R8 artifacts; shape is still re-verified before use.
     */
    ResolvedTarget resolveModelInterface() {
        try {
            Class<?> type = Class.forName(MODEL_INTERFACE_NAME, false, loader);
            ResolvedTarget target = new ResolvedTarget(
                    XTargetResolver.KEY_MODEL_URT_ITEM,
                    XTargetResolver.TIER_SEMANTIC_NAME,
                    DescriptorUtils.classDescriptorOf(type), "",
                    0, false);
            String rejected = XTargetVerifier.rejectModelInterface(target, loader);
            if (rejected != null) {
                log.info("resolver target=model.urtItemInterface path=semantic_name"
                        + " rejected=" + rejected);
                return null;
            }
            log.info("resolver target=model.urtItemInterface tier=semantic_name"
                    + " descriptor=" + target.classDescriptor);
            return target;
        } catch (Throwable throwable) {
            log.info("resolver target=model.urtItemInterface path=semantic_name"
                    + " unavailable reason=" + throwable);
            return null;
        }
    }

    /**
     * The app's own static isAd(UrtTimelineItem) predicate, located purely by
     * structural fingerprint: static, (modelInterface) -> boolean, declared
     * next to a (modelInterface) -> TimelinePromotedMetadata companion.
     */
    ResolvedTarget resolveAdHelper(Class<?> modelInterface) {
        if (modelInterface == null) {
            return null;
        }
        try {
            MethodDataList raw = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes(MODEL_INTERFACE_NAME)
                            .returnType("boolean")));
            List<MethodData> candidates = new ArrayList<>();
            for (MethodData method : raw) {
                try {
                    Method instance = method.getMethodInstance(loader);
                    if (instance != null
                            && Modifier.isStatic(instance.getModifiers())
                            && hasPromotedMetadataCompanion(instance.getDeclaringClass())) {
                        candidates.add(method);
                    }
                } catch (Throwable ignored) {
                }
            }
            log.info("resolver target=model.adHelperIsAd candidateCount=" + candidates.size());
            if (candidates.size() != 1) {
                log.info("resolver target=model.adHelperIsAd rejected=ambiguousOrEmpty"
                        + " count=" + candidates.size());
                return null;
            }
            Method instance = candidates.get(0).getMethodInstance(loader);
            ResolvedTarget target = new ResolvedTarget(
                    XTargetResolver.KEY_MODEL_AD_HELPER,
                    XTargetResolver.TIER_STRONG,
                    DescriptorUtils.classDescriptorOf(instance.getDeclaringClass()),
                    candidates.get(0).getDescriptor(), 0, false);
            String rejected = XTargetVerifier.rejectAdHelper(target, loader, modelInterface);
            if (rejected != null) {
                log.info("resolver target=model.adHelperIsAd rejected=" + rejected);
                return null;
            }
            log.info("resolver target=model.adHelperIsAd tier=fingerprint_strong"
                    + " descriptor=" + target.methodDescriptor
                    + " semantics=runtimeWitnessRequired");
            return target;
        } catch (Throwable throwable) {
            log.error("resolver target=model.adHelperIsAd queryFailed", throwable);
            return null;
        }
    }

    /** Same declaring class also exposes (UrtTimelineItem) -> TimelinePromotedMetadata. */
    private boolean hasPromotedMetadataCompanion(Class<?> declaring) {
        try {
            Class<?> promoted = Class.forName(PROMOTED_METADATA_NAME, false, loader);
            Class<?> model = Class.forName(MODEL_INTERFACE_NAME, false, loader);
            for (Method candidate : declaring.getDeclaredMethods()) {
                if (Modifier.isStatic(candidate.getModifiers())
                        && candidate.getReturnType() == promoted
                        && candidate.getParameterTypes().length == 1
                        && candidate.getParameterTypes()[0] == model) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void runStringEntry(Map<String, CandidateScoring.ScoredCandidate> merged,
                                String entryName, String needle, boolean withShape) {
        try {
            MethodMatcher matcher = MethodMatcher.create().usingStrings(needle);
            if (withShape) {
                matcher.paramTypes("java.lang.Object", "kotlin.coroutines.Continuation")
                        .returnType("java.lang.Object");
            }
            MethodDataList raw = bridge.findMethod(FindMethod.create().matcher(matcher));
            log.info("resolver target=urt_emit entry=" + entryName + " hits=" + raw.size());
            collectDexTier(merged, raw, entryName);
        } catch (Throwable throwable) {
            log.error("resolver target=urt_emit entry=" + entryName + " queryFailed", throwable);
        }
    }

    private void runStructuralEntry(Map<String, CandidateScoring.ScoredCandidate> merged) {
        try {
            MethodDataList raw = bridge.findMethod(FindMethod.create()
                    .searchPackages(STRUCTURAL_SEARCH_PACKAGE)
                    .matcher(MethodMatcher.create()
                            .name("emit")
                            .paramTypes("java.lang.Object", "kotlin.coroutines.Continuation")
                            .returnType("java.lang.Object")));
            log.info("resolver target=urt_emit entry=structural hits=" + raw.size());
            collectDexTier(merged, raw, "structural");
        } catch (Throwable throwable) {
            log.error("resolver target=urt_emit entry=structural queryFailed", throwable);
        }
    }

    private List<Method> queryLegacyFallback() {
        try {
            Class<?> seed = Class.forName(LEGACY_EMIT_CLASS_12_3_1, false, loader);
            Method method = seed.getDeclaredMethod("emit", objectType, continuationType);
            method.setAccessible(true);
            log.info("resolver target=urt_emit entry=legacy_seed hits=1 class="
                    + LEGACY_EMIT_CLASS_12_3_1);
            return Collections.singletonList(method);
        } catch (Throwable throwable) {
            log.info("resolver target=urt_emit entry=legacy_seed unavailable reason="
                    + throwable.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private void collectDexTier(Map<String, CandidateScoring.ScoredCandidate> merged,
                                MethodDataList entryResults, String entryName) {
        if (entryResults == null) {
            return;
        }
        for (MethodData method : entryResults) {
            String descriptor;
            try {
                descriptor = method.getDescriptor();
                recordStringUsage(method);
            } catch (Throwable throwable) {
                log.info("candidate target=urt_emit entry=" + entryName
                        + " rejected=unreadable reason=" + throwable);
                continue;
            }
            addCandidate(merged, descriptor, entryName);
        }
    }

    private void collectSeedTier(Map<String, CandidateScoring.ScoredCandidate> merged,
                                 List<Method> seeds) {
        for (Method seed : seeds) {
            addCandidate(merged, dexDescriptorOf(seed), "legacy_seed");
        }
    }

    private void recordStringUsage(MethodData method) {
        try {
            Set<String> usage = new HashSet<>();
            for (String value : method.getUsingStrings()) {
                usage.add(value);
            }
            stringUsageByDescriptor.put(method.getDescriptor(), usage);
        } catch (Throwable ignored) {
        }
    }

    private void addCandidate(Map<String, CandidateScoring.ScoredCandidate> merged,
                              String descriptor, String entryName) {
        int arrow = descriptor.indexOf("->");
        int open = descriptor.indexOf('(', arrow);
        if (arrow <= 0 || open <= arrow) {
            return;
        }
        String classDescriptor = descriptor.substring(0, arrow);
        String methodName = descriptor.substring(arrow + 2, open);

        Method instance = DescriptorUtils.methodForDescriptor(descriptor, loader);
        if (instance == null) {
            log.info("candidate target=urt_emit entry=" + entryName
                    + " rejected=notLoadable descriptor=" + descriptor);
            return;
        }
        Class<?>[] params = instance.getParameterTypes();
        boolean cpsShape = params.length == 2
                && params[0] == objectType && params[1] == continuationType
                && instance.getReturnType() == objectType;
        boolean conflict = Modifier.isStatic(instance.getModifiers())
                || Modifier.isAbstract(instance.getModifiers())
                || !cpsShape;
        XTargetVerifier.TriState flowOverride = XTargetVerifier.interfaceOverrideShape(instance);
        boolean inUrtPackage = classDescriptor.startsWith("Lcom/x/repositories/urt/");
        Set<String> usingStrings = stringUsageByDescriptor.containsKey(descriptor)
                ? stringUsageByDescriptor.get(descriptor)
                : Collections.<String>emptySet();

        CandidateScoring.CandidateFeatures features = new CandidateScoring.CandidateFeatures(
                usingStrings, methodName, cpsShape, conflict, flowOverride, inUrtPackage);
        CandidateScoring.Report report = CandidateScoring.scoreReport(features);
        if (report.score <= 0) {
            log.info("candidate target=urt_emit entry=" + entryName
                    + " rejected=lowScore score=" + report.score
                    + " descriptor=" + descriptor + " evidence=" + report.evidence);
            return;
        }
        merged.put(descriptor, new CandidateScoring.ScoredCandidate(
                classDescriptor, descriptor, report.score, report.evidence));
    }

    /** DexKit-style descriptor for a reflected Method. */
    static String dexDescriptorOf(Method method) {
        StringBuilder sb = new StringBuilder(DescriptorUtils.classDescriptorOf(
                method.getDeclaringClass())).append("->").append(method.getName()).append('(');
        for (Class<?> type : method.getParameterTypes()) {
            sb.append(typeDescriptorOf(type));
        }
        sb.append(')').append(typeDescriptorOf(method.getReturnType()));
        return sb.toString();
    }

    private static String typeDescriptorOf(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == short.class) return "S";
        if (type == char.class) return "C";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        String name = type.getName();
        if (name.startsWith("[")) {
            return name.replace('.', '/');
        }
        return "L" + name.replace('.', '/') + ";";
    }
}
