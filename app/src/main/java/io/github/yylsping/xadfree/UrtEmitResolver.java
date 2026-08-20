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
 * <p>Tiers (all tiers run; results merge by descriptor and re-score):
 * <ol>
 *   <li>strong — the high-entropy ad-removal log string plus the exact
 *       (Object, Continuation) -&gt; Object CPS shape;</li>
 *   <li>weak — the spacing scribe metric string without shape narrowing;</li>
 *   <li>fallback — the 12.3.1 historical seed class via plain reflection
 *       (contributes structural evidence only).</li>
 * </ol>
 * Every candidate passes through {@link CandidateScoring}; nothing is
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

    private final DexKitBridge bridge;
    private final ClassLoader loader;
    private final ModuleLog log;
    private final Class<?> objectType;
    private final Class<?> continuationType;

    /** DexKit per-method string usage, captured while tier results are at hand. */
    private final Map<String, Set<String>> stringUsageByDescriptor = new LinkedHashMap<>();

    UrtEmitResolver(DexKitBridge bridge, ClassLoader loader, ModuleLog log,
                    Class<?> objectType, Class<?> continuationType) {
        this.bridge = bridge;
        this.loader = loader;
        this.log = log;
        this.objectType = objectType;
        this.continuationType = continuationType;
    }

    /** Ranked emit candidates, best first. May be empty (fail-closed). */
    List<CandidateScoring.ScoredCandidate> resolveEmitCandidates() {
        Map<String, CandidateScoring.ScoredCandidate> merged = new LinkedHashMap<>();
        collectDexTier(merged, queryStrong(), XTargetResolver.TIER_STRONG);
        collectDexTier(merged, queryWeak(), XTargetResolver.TIER_WEAK);
        collectSeedTier(merged, queryLegacyFallback());

        List<CandidateScoring.ScoredCandidate> ranked = new ArrayList<>(merged.values());
        ranked.sort((a, b) -> Integer.compare(b.score, a.score));
        log.info("resolver target=urt_emit candidateCount=" + ranked.size());
        for (CandidateScoring.ScoredCandidate candidate : ranked) {
            log.info("candidate target=urt_emit descriptor=" + candidate.methodDescriptor
                    + " score=" + candidate.score + " evidence=" + candidate.evidence);
        }
        if (ranked.isEmpty()) {
            log.info("resolver target=urt_emit tier=all failed=unresolved failClosed=true");
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
                    + " descriptor=" + target.methodDescriptor);
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

    private MethodDataList queryStrong() {
        try {
            MethodDataList raw = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("java.lang.Object", "kotlin.coroutines.Continuation")
                            .returnType("java.lang.Object")
                            .usingStrings(CandidateScoring.STRING_PRIMARY)));
            log.info("resolver target=urt_emit tier=strong hits=" + raw.size());
            return raw;
        } catch (Throwable throwable) {
            log.error("resolver target=urt_emit tier=strong queryFailed", throwable);
            return null;
        }
    }

    private MethodDataList queryWeak() {
        try {
            MethodDataList raw = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings(CandidateScoring.STRING_SPACING_METRIC)));
            log.info("resolver target=urt_emit tier=weak hits=" + raw.size());
            return raw;
        } catch (Throwable throwable) {
            log.error("resolver target=urt_emit tier=weak queryFailed", throwable);
            return null;
        }
    }

    private List<Method> queryLegacyFallback() {
        try {
            Class<?> seed = Class.forName(LEGACY_EMIT_CLASS_12_3_1, false, loader);
            Method method = seed.getDeclaredMethod("emit", objectType, continuationType);
            method.setAccessible(true);
            log.info("resolver target=urt_emit tier=fallback_compat hits=1 class="
                    + LEGACY_EMIT_CLASS_12_3_1);
            return Collections.singletonList(method);
        } catch (Throwable throwable) {
            log.info("resolver target=urt_emit tier=fallback_compat unavailable reason="
                    + throwable.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private void collectDexTier(Map<String, CandidateScoring.ScoredCandidate> merged,
                                MethodDataList tierResults, String tier) {
        if (tierResults == null) {
            return;
        }
        for (MethodData method : tierResults) {
            String descriptor;
            try {
                descriptor = method.getDescriptor();
                recordStringUsage(method);
            } catch (Throwable throwable) {
                log.info("candidate target=urt_emit tier=" + tier
                        + " rejected=unreadable reason=" + throwable);
                continue;
            }
            addCandidate(merged, descriptor, tier);
        }
    }

    private void collectSeedTier(Map<String, CandidateScoring.ScoredCandidate> merged,
                                 List<Method> seeds) {
        for (Method seed : seeds) {
            addCandidate(merged, dexDescriptorOf(seed), XTargetResolver.TIER_FALLBACK);
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
                              String descriptor, String tier) {
        int arrow = descriptor.indexOf("->");
        int open = descriptor.indexOf('(', arrow);
        if (arrow <= 0 || open <= arrow) {
            return;
        }
        String classDescriptor = descriptor.substring(0, arrow);
        String methodName = descriptor.substring(arrow + 2, open);

        Method instance = DescriptorUtils.methodForDescriptor(descriptor, loader);
        if (instance == null) {
            log.info("candidate target=urt_emit tier=" + tier
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
        boolean flowCollector = XTargetVerifier.declaresFlowCollector(
                instance.getDeclaringClass(), loader);
        boolean inUrtPackage = classDescriptor.startsWith("Lcom/x/repositories/urt/");
        Set<String> usingStrings = stringUsageByDescriptor.containsKey(descriptor)
                ? stringUsageByDescriptor.get(descriptor)
                : Collections.<String>emptySet();

        CandidateScoring.CandidateFeatures features = new CandidateScoring.CandidateFeatures(
                usingStrings, methodName, cpsShape, conflict, flowCollector, inUrtPackage);
        CandidateScoring.Report report = CandidateScoring.scoreReport(features);
        if (report.score <= 0) {
            log.info("candidate target=urt_emit tier=" + tier
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
