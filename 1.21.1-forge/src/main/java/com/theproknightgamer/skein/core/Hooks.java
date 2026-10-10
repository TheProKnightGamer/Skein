package com.theproknightgamer.skein.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which of Skein's injections are really in the game, and what is switched off
 * because one is not.
 *
 * <p>Skein hooks more than a hundred places in vanilla's tick, and any other
 * mod that rewrites one of those methods can take a hook away. With Mixin's
 * usual {@code require = 1} that stops the game from loading: a threading mod
 * that crashes every pack containing a mod which touches the same method is
 * not compatible with that mod. Silently running without the hook is worse --
 * most of Skein's hooks exist in pairs (collect a tick here, run it there;
 * open a bracket here, close it there), and half a pair on a parallel path is
 * lost ticks, doubled explosion drops, or a race.
 *
 * <p>So every injector is applied with {@code require = 0}, and this class
 * checks each one once the class is finished: for every method the injector
 * names, the merged handler has to be called from that method. "Finished" is
 * later than Mixin's own post-apply hook, which runs before MixinExtras applies
 * its wrappers; the check is a Mixin extension registered behind MixinExtras's,
 * so it sees the class as it will be defined. An
 * injector that did not land turns off the features its {@link HookFor} names
 * -- all of Skein when it names none -- and the log says which hook, in which
 * method, and which other mods' mixins changed that class. The server keeps
 * running, with vanilla behaviour wherever the hook would have acted.
 *
 * <p>This check is stricter than {@code require = 1}, which counts injections
 * across every method an injector names: a bracket read from three methods
 * passed with one of them missing.
 *
 * <p>Loaded while Mixin prepares its configs, so nothing here may touch a
 * Minecraft class.
 */
public final class Hooks {
    private Hooks() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("com.theproknightgamer.skein.Skein");

    /** What a missing hook can switch off. */
    public enum Feature {
        /** Everything; the default for a hook with no {@link HookFor}. */
        CORE("all of Skein"),
        DIMENSIONS("dimension threading"),
        SCHEDULED_TICKS("parallel scheduled ticks"),
        RANDOM_TICKS("parallel random ticks"),
        ENTITIES("parallel entities"),
        BLOCK_ENTITIES("parallel block entities"),
        EXPLOSIONS("explosion optimisations"),
        EXPLOSION_RAYS("explosion rays on the pool"),
        ENTITY_TRACKING("entity tracking optimisations"),
        PROJECTILE_QUERIES("projectile query shortcuts"),
        /** Not threading at all; see {@link CollisionQueries}. Off when another mod changes the collision rules. */
        COLLISION_QUERIES("entity collision shortcuts"),
        CHUNK_SCAN("parallel chunk walks"),
        PISTON_PUSHES("parallel piston pushes"),
        PARALLEL_SAVING("parallel autosave"),
        /**
         * Every feature that runs world code on more than one thread inside a
         * level; dimension threading keeps going. For the hooks that only act
         * while a pass is deferring structural writes.
         */
        IN_LEVEL("every in-level feature");

        private final String label;

        Feature(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    private static final Set<Feature> IN_LEVEL_MEMBERS = Collections.unmodifiableSet(EnumSet.of(
            Feature.SCHEDULED_TICKS, Feature.RANDOM_TICKS, Feature.ENTITIES, Feature.BLOCK_ENTITIES,
            Feature.EXPLOSIONS, Feature.EXPLOSION_RAYS, Feature.ENTITY_TRACKING, Feature.PROJECTILE_QUERIES,
            Feature.CHUNK_SCAN, Feature.PISTON_PUSHES));

    /**
     * An injector that is not in the class it was written for -- or, when
     * {@code yielded}, one that is, but shares the stretch of code it takes
     * over with another mod's change; see {@link HookFor#yieldsUntil}.
     */
    public record Missing(String mixin, String handler, String target, String method,
                          Set<Feature> features, Set<String> otherMixins, boolean yielded) {
        Missing(String mixin, String handler, String target, String method,
                Set<Feature> features, Set<String> otherMixins) {
            this(mixin, handler, target, method, features, otherMixins, false);
        }

        public String describe() {
            return simpleName(this.mixin) + "." + this.handler + " in " + simpleName(this.target) + "."
                    + this.method + (this.otherMixins.isEmpty() ? "" : " (also changed by " + this.otherMixins + ")");
        }
    }

    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;");

    private static final String HOOK_FOR = Type.getDescriptor(HookFor.class);

    private static final Set<String> expected = new LinkedHashSet<>();
    private static final Set<String> applied = new HashSet<>();
    /** Skein mixins applied to a class whose transformation has not finished yet. */
    private record Pending(ClassNode node, String mixin, IMixinInfo info) {}
    private static final Map<String, List<Pending>> pending = new HashMap<>();
    private static boolean checkInstalled;
    private static final List<Missing> missing = new ArrayList<>();
    private static int verified;
    private static volatile Set<Feature> blocked = Collections.unmodifiableSet(EnumSet.noneOf(Feature.class));
    private static volatile Runnable onChange;

    /** True unless a missing hook has switched {@code feature} off. */
    public static boolean allows(Feature feature) {
        Set<Feature> off = blocked;
        if (off.isEmpty()) {
            return true;
        }
        if (off.contains(Feature.CORE) || off.contains(feature)) {
            return false;
        }
        return !(off.contains(Feature.IN_LEVEL) && IN_LEVEL_MEMBERS.contains(feature));
    }

    /** Everything a missing hook has switched off, for the log and {@code /skein status}. */
    public static Set<Feature> blocked() {
        return blocked;
    }

    public static synchronized List<Missing> missing() {
        return List.copyOf(missing);
    }

    /** False if a check found the Skein handler of that name missing somewhere. */
    public static synchronized boolean landed(String handler) {
        for (Missing hook : missing) {
            if (!hook.yielded() && hook.handler().equals(handler)) {
                return false;
            }
        }
        return true;
    }

    public static synchronized int verified() {
        return verified;
    }

    /** Run when a hook goes missing after startup, so the config snapshot can be re-masked. */
    public static void onChange(Runnable listener) {
        onChange = listener;
    }

    /** The classes Skein's mixins are written against; from the config plugin. */
    public static synchronized void expect(Collection<String> targets) {
        for (String target : targets) {
            expected.add(target.replace('/', '.'));
        }
    }

    /**
     * The targets of the mixins the config plugin adds for other mods, which
     * Mixin hands the plugin only after it has asked for its targets -- so
     * that those classes are loaded, and their hooks checked, before the
     * first tick as well.
     */
    public static void expectMixins(String mixinPackage, Collection<String> mixins) {
        List<String> targets = new ArrayList<>();
        for (String mixin : mixins) {
            ClassNode node = readClass(mixinPackage + "." + mixin);
            if (node == null || node.invisibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode annotation : node.invisibleAnnotations) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc) || annotation.values == null) {
                    continue;
                }
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (annotation.values.get(i + 1) instanceof List<?> list) {
                        for (Object value : list) {
                            if (value instanceof Type type) {
                                targets.add(type.getClassName());
                            } else if (value instanceof String name && "targets".equals(annotation.values.get(i))) {
                                targets.add(name);
                            }
                        }
                    }
                }
            }
        }
        expect(targets);
    }

    /**
     * Notes that a Skein mixin was applied, from the config plugin. The check
     * waits for {@link Check}, which runs once every other injector -- another
     * mod's included -- has had its turn at the class.
     */
    public static void applied(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo info) {
        synchronized (Hooks.class) {
            pending.computeIfAbsent(targetClassName, key -> new ArrayList<>())
                    .add(new Pending(target, mixinClassName, info));
            if (checkInstalled) {
                return;
            }
            checkInstalled = true;
        }
        install();
    }

    /**
     * Registers {@link Check} behind every extension already present.
     * MixinExtras registers its own the first time a config uses it, which is
     * before any class Skein targets is transformed; this runs at the first
     * such class, so the check always comes after them.
     */
    private static void install() {
        try {
            MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
            if (environment.getActiveTransformer() instanceof IMixinTransformer transformer
                    && transformer.getExtensions() instanceof Extensions extensions) {
                extensions.add(new Check());
                extensions.select(environment);
                return;
            }
            LOGGER.warn("Skein: no Mixin extension registry; hooks will be checked when the server starts");
        } catch (Throwable unavailable) {
            LOGGER.warn("Skein: could not register the hook check; hooks will be checked when the server starts",
                    unavailable);
        }
    }

    /** Runs after MixinExtras's extensions, on the finished class. */
    private static final class Check implements IExtension {
        @Override
        public boolean checkActive(MixinEnvironment environment) {
            return true;
        }

        @Override
        public void preApply(ITargetClassContext context) {
        }

        @Override
        public void postApply(ITargetClassContext context) {
            finish(context.getClassInfo().getClassName(), context.getClassNode());
        }

        @Override
        public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
        }
    }

    private static void finish(String targetClassName, ClassNode finished) {
        List<Pending> mixins;
        synchronized (Hooks.class) {
            mixins = pending.remove(targetClassName.replace('/', '.'));
        }
        if (mixins == null) {
            return;
        }
        for (Pending mixin : mixins) {
            try {
                verify(targetClassName.replace('/', '.'), finished != null ? finished : mixin.node(),
                        mixin.mixin(), mixin.info());
            } catch (Throwable unexpected) {
                unverifiable(targetClassName, mixin.mixin(), unexpected);
            }
        }
    }

    /** Checks whatever the extension did not get to; the held nodes are finished by now. */
    private static void finishAll() {
        List<String> left;
        synchronized (Hooks.class) {
            left = new ArrayList<>(pending.keySet());
        }
        for (String target : left) {
            finish(target, null);
        }
    }

    /** Checks one mixin against the finished class it was applied to. */
    private static void verify(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo info) {
        ClassNode mixin = mixinNode(mixinClassName, info);
        Set<Missing> lost = new LinkedHashSet<>();
        int checked = 0;
        if (mixin == null) {
            lost.add(new Missing(mixinClassName, "*", targetClassName, "*", EnumSet.of(Feature.CORE), Set.of()));
        } else {
            Set<Feature> classDefault = features(mixin.invisibleAnnotations, EnumSet.of(Feature.CORE));
            Set<String> others = null;
            for (MethodNode handler : mixin.methods) {
                AnnotationNode injector = injector(handler);
                if (injector == null) {
                    continue;
                }
                checked++;
                Set<Feature> features = features(handler.invisibleAnnotations, classDefault);
                Set<String> merged = mergedNames(target, handler.name);
                List<String> selectors = stringList(injector, "method");
                if (selectors.isEmpty()) {
                    selectors = List.of("?");
                }
                String until = yieldsUntil(handler.invisibleAnnotations);
                Set<Feature> yields = yields(handler.invisibleAnnotations, features);
                for (String selector : selectors) {
                    warnIfAmbiguous(target, mixinClassName, handler.name, selector);
                    if (!landed(target, selector, merged)) {
                        if (others == null) {
                            others = otherMixins(targetClassName, info);
                        }
                        lost.add(new Missing(mixinClassName, handler.name, targetClassName, selector,
                                features, others));
                    } else if (until != null) {
                        Set<String> sharing = sharedStretch(target, selector, merged, mergedNames(target, until));
                        if (yields.contains(Feature.EXPLOSION_RAYS)) {
                            // Carpet's block-read redirect is checked per
                            // explosion instead; see CarpetCompat.
                            sharing.removeIf(CarpetCompat::isBlockDamageRedirect);
                        }
                        if (!sharing.isEmpty()) {
                            lost.add(new Missing(mixinClassName, handler.name, targetClassName, selector,
                                    yields, sharing, true));
                        }
                    }
                }
            }
        }
        record(targetClassName, checked, lost);
    }

    /** A hook that could not be checked at all is treated as missing. */
    public static void unverifiable(String targetClassName, String mixinClassName, Throwable why) {
        LOGGER.error("Skein: could not check {} against {}; treating it as missing", mixinClassName,
                targetClassName, why);
        record(targetClassName, 0, Set.of(new Missing(mixinClassName, "*", targetClassName, "*",
                EnumSet.of(Feature.CORE), Set.of())));
    }

    private static void record(String targetClassName, int checked, Set<Missing> lost) {
        Runnable listener = null;
        synchronized (Hooks.class) {
            applied.add(targetClassName);
            verified += checked;
            if (lost.isEmpty()) {
                return;
            }
            missing.addAll(lost);
            EnumSet<Feature> off = blocked.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(blocked);
            for (Missing hook : lost) {
                off.addAll(hook.features());
            }
            blocked = Collections.unmodifiableSet(off);
            listener = onChange;
        }
        for (Missing hook : lost) {
            if (hook.yielded()) {
                LOGGER.info("Skein: another mod also changes the code {} takes over, so Skein leaves it to them and "
                        + "switches off: {}", hook.describe(), labels(hook.features()));
            } else if (hook.features().isEmpty()) {
                LOGGER.info("Skein: optional hook {} was not applied; that optimisation is off", hook.describe());
            } else {
                LOGGER.warn("Skein: hook {} was not applied, most likely because another mod rewrote that method. "
                        + "Switching off: {}", hook.describe(), labels(hook.features()));
            }
        }
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Loads every class Skein's mixins target that has not been loaded yet, so
     * that every hook has been checked before the first tick instead of on the
     * first explosion. Loading is all this does: nothing is initialised.
     */
    public static void loadTargets(ClassLoader loader) {
        List<String> toLoad;
        synchronized (Hooks.class) {
            toLoad = new ArrayList<>(expected);
            toLoad.removeAll(applied);
            toLoad.removeAll(pending.keySet());
        }
        for (String name : toLoad) {
            try {
                Class.forName(name, false, loader);
            } catch (Throwable notLoadable) {
                LOGGER.debug("Skein: could not load mixin target {} ahead of time", name, notLoadable);
            }
        }
        finishAll();
    }

    /** One line for the log or {@code /skein status}. */
    public static String summary() {
        Set<Feature> off = blocked;
        List<Missing> lost = missing();
        long yielded = lost.stream().filter(Missing::yielded).count();
        long required = lost.stream().filter(hook -> !hook.yielded() && !hook.features().isEmpty()).count();
        long optional = lost.size() - yielded - required;
        String line = verified() + " hooks checked, " + required + " missing";
        if (optional > 0) {
            line += ", " + optional + " optional not applied";
        }
        if (yielded > 0) {
            line += ", " + yielded + " left to other mods";
        }
        return off.isEmpty() ? line : line + "; switched off: " + labels(off);
    }

    public static String labels(Set<Feature> features) {
        List<String> labels = new ArrayList<>();
        for (Feature feature : features) {
            labels.add(feature.label());
        }
        return String.join(", ", labels);
    }

    // --- checking one injector --------------------------------------------------

    /**
     * The mixin as it was compiled. Mixin's own copy is not that: MixinExtras
     * rewrites a handler that takes {@code @Local} or {@code @Share} into a
     * wrapper annotation, which would hide the handler from the check, so the
     * class file comes first and Mixin's node is only the fallback.
     */
    private static ClassNode mixinNode(String mixinClassName, IMixinInfo info) {
        ClassNode compiled = readClass(mixinClassName);
        if (compiled != null) {
            return compiled;
        }
        try {
            ClassNode node = info.getClassNode(ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            return node != null && node.methods != null ? node : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** One of Skein's own classes, from its class file, without loading it. */
    private static ClassNode readClass(String className) {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = Hooks.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            return node;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static AnnotationNode injector(MethodNode method) {
        AnnotationNode found = injector(method.visibleAnnotations);
        return found != null ? found : injector(method.invisibleAnnotations);
    }

    private static AnnotationNode injector(List<AnnotationNode> annotations) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                AnnotationNode unwrapped = unwrap(annotation);
                if (INJECTORS.contains(unwrapped.desc)) {
                    return unwrapped;
                }
            }
        }
        return null;
    }

    /** The injector inside one of MixinExtras's wrapper annotations, which keep it as {@code original}. */
    private static AnnotationNode unwrap(AnnotationNode annotation) {
        for (int depth = 0; depth < 4 && annotation.desc.startsWith("Lcom/llamalad7/mixinextras/")
                && !INJECTORS.contains(annotation.desc) && annotation.values != null; depth++) {
            AnnotationNode inner = null;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("original".equals(annotation.values.get(i))
                        && annotation.values.get(i + 1) instanceof AnnotationNode original) {
                    inner = original;
                }
            }
            if (inner == null) {
                break;
            }
            annotation = inner;
        }
        return annotation;
    }

    private static Set<Feature> features(List<AnnotationNode> annotations, Set<Feature> fallback) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (!HOOK_FOR.equals(annotation.desc)) {
                    continue;
                }
                EnumSet<Feature> features = EnumSet.noneOf(Feature.class);
                if (annotation.values != null) {
                    for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                        if ("value".equals(annotation.values.get(i))
                                && annotation.values.get(i + 1) instanceof List<?> list) {
                            for (Object value : list) {
                                if (value instanceof String[] pair && pair.length == 2) {
                                    features.add(Feature.valueOf(pair[1]));
                                }
                            }
                        }
                    }
                }
                return features;
            }
        }
        return fallback;
    }

    private static Set<Feature> yields(List<AnnotationNode> annotations, Set<Feature> fallback) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (!HOOK_FOR.equals(annotation.desc) || annotation.values == null) {
                    continue;
                }
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if ("yields".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof List<?> list
                            && !list.isEmpty()) {
                        EnumSet<Feature> features = EnumSet.noneOf(Feature.class);
                        for (Object value : list) {
                            if (value instanceof String[] pair && pair.length == 2) {
                                features.add(Feature.valueOf(pair[1]));
                            }
                        }
                        return features;
                    }
                }
            }
        }
        return fallback;
    }

    private static String yieldsUntil(List<AnnotationNode> annotations) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (HOOK_FOR.equals(annotation.desc)) {
                    List<String> until = stringList(annotation, "yieldsUntil");
                    return until.isEmpty() || until.getFirst().isEmpty() ? null : until.getFirst();
                }
            }
        }
        return null;
    }

    /**
     * A merged handler: {@code prefix$<class uid><hex method uid>$<source id>$name}.
     * The group is everything after the uid, so Skein's own start with
     * {@code skein$}.
     */
    private static final java.util.regex.Pattern HANDLER =
            java.util.regex.Pattern.compile("^[A-Za-z]+\\$[a-z]+[0-9a-f]{3,}\\$(.+)$");
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    /**
     * Whose code, besides Skein's, runs between the call to one of
     * {@code from} and the next call to one of {@code to}, in the methods
     * {@code selector} names -- or anywhere in one another mod has merged or
     * overwritten. Another mod's wrapper nested inside one of Skein's is
     * reached through the bridge Skein's handler is handed, so bridges are
     * followed. When {@code to} sits in a loop that starts inside the stretch,
     * the stretch runs to the end of that loop's body: the work Skein takes
     * over is the whole iteration, not just the part before the end marker.
     */
    private static Set<String> sharedStretch(ClassNode target, String selector, Set<String> from, Set<String> to) {
        Set<String> others = new TreeSet<>();
        String name = selector;
        String desc = null;
        int paren = name.indexOf('(');
        if (paren >= 0) {
            desc = name.substring(paren);
            name = name.substring(0, paren);
        }
        String wrapped = name + "$mixinextras$wrapped$";
        List<MethodNode> candidates = new ArrayList<>();
        boolean started = false;
        for (MethodNode method : target.methods) {
            if ((method.name.equals(name) || method.name.startsWith(wrapped))
                    && (desc == null || method.name.startsWith(wrapped) || desc.equals(method.desc))
                    && method.instructions != null) {
                candidates.add(method);
                started |= calls(method, target.name, from);
            }
        }
        for (MethodNode method : candidates) {
            String merger = mergedBy(method);
            if (merger != null && !merger.startsWith("com.theproknightgamer.skein.")) {
                others.add(merger);
            }
            AbstractInsnNode[] insns = method.instructions.toArray();
            int start = -1;
            int end = -1;
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof MethodInsnNode call && call.owner.equals(target.name)) {
                    if (start < 0 && from.contains(call.name)) {
                        start = i;
                    } else if (start >= 0 && to.contains(call.name)) {
                        end = i;
                        break;
                    }
                }
            }
            if (start < 0) {
                if (started) {
                    // The stretch is in a sibling: this is the shell a
                    // @WrapMethod left, or another overload.
                    continue;
                }
                // Skein's start of the stretch is only reached through another
                // mod's wrapper of the same call: that mod holds the stretch.
                for (AbstractInsnNode insn : insns) {
                    foreignHandlers(target, insn, others, new HashSet<>());
                }
                continue;
            }
            if (end < 0) {
                end = insns.length;
            } else {
                int last = end;
                for (int i = end + 1; i < insns.length; i++) {
                    if (insns[i] instanceof org.objectweb.asm.tree.JumpInsnNode jump) {
                        int back = method.instructions.indexOf(jump.label);
                        if (back > start && back <= end) {
                            last = i;
                        }
                    }
                }
                end = last;
            }
            Set<String> seen = new HashSet<>();
            for (int i = start + 1; i < end; i++) {
                foreignHandlers(target, insns[i], others, seen);
            }
        }
        return others;
    }

    /**
     * Adds the other mods' handlers {@code insn} calls, directly or through
     * MixinExtras bridges, following bridges not yet in {@code seen}.
     */
    private static void foreignHandlers(ClassNode target, AbstractInsnNode insn, Set<String> into, Set<String> seen) {
        List<String> names = new ArrayList<>();
        if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)) {
            names.add(call.name);
        } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
            for (Object arg : indy.bsmArgs) {
                if (arg instanceof Handle handle && handle.getOwner().equals(target.name)) {
                    names.add(handle.getName());
                }
            }
        }
        for (String name : names) {
            if (isBridge(name)) {
                if (!seen.add(name)) {
                    continue;
                }
                for (MethodNode bridge : target.methods) {
                    if (bridge.name.equals(name) && bridge.instructions != null) {
                        for (AbstractInsnNode inner = bridge.instructions.getFirst(); inner != null;
                                inner = inner.getNext()) {
                            foreignHandlers(target, inner, into, seen);
                        }
                    }
                }
                continue;
            }
            java.util.regex.Matcher handler = HANDLER.matcher(name);
            if (handler.matches() && !handler.group(1).startsWith("skein$")) {
                into.add(handler.group(1));
            }
        }
    }

    /** The mixin that merged or overwrote {@code method}, from Mixin's own marker, or null. */
    private static String mergedBy(MethodNode method) {
        for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (AnnotationNode annotation : list) {
                if (MERGED.equals(annotation.desc)) {
                    List<String> mixin = stringList(annotation, "mixin");
                    return mixin.isEmpty() ? null : mixin.getFirst();
                }
            }
        }
        return null;
    }

    private static List<String> stringList(AnnotationNode annotation, String key) {
        List<String> values = new ArrayList<>();
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (!key.equals(annotation.values.get(i))) {
                    continue;
                }
                Object value = annotation.values.get(i + 1);
                if (value instanceof String single) {
                    values.add(single);
                } else if (value instanceof List<?> list) {
                    for (Object entry : list) {
                        if (entry instanceof String string) {
                            values.add(string);
                        }
                    }
                }
            }
        }
        return values;
    }

    /**
     * Mixin renames a handler as it merges it -- {@code handler$zbb000$skein$init}
     * for {@code skein$init} -- and MixinExtras does the same with its own
     * prefixes, so a merged handler is recognised by its suffix.
     */
    private static Set<String> mergedNames(ClassNode target, String handler) {
        Set<String> names = new HashSet<>();
        String suffix = "$" + handler;
        for (MethodNode method : target.methods) {
            if (method.name.equals(handler) || method.name.endsWith(suffix)) {
                names.add(method.name);
            }
        }
        return names;
    }

    /**
     * A selector without a descriptor makes Mixin pick the first method of
     * that name, so on an overloaded method the others are silently left
     * unhooked -- which is how {@code ServerLevel.neighborChanged}'s second
     * overload stayed on the shared neighbour updater. Skein's own selectors
     * name descriptors wherever that matters; this says so if one does not.
     */
    private static void warnIfAmbiguous(ClassNode target, String mixin, String handler, String selector) {
        if (selector.indexOf('(') >= 0 || selector.endsWith("*")) {
            return;
        }
        int overloads = 0;
        for (MethodNode method : target.methods) {
            if (method.name.equals(selector) && (method.access & org.objectweb.asm.Opcodes.ACC_BRIDGE) == 0) {
                overloads++;
            }
        }
        if (overloads > 1) {
            LOGGER.warn("Skein: {}.{} names {} without a descriptor, and {} has {} methods of that name; only "
                    + "the first is hooked", simpleName(mixin), handler, selector, simpleName(target.name.replace('/', '.')),
                    overloads);
        }
    }

    /** Whether some method {@code selector} names calls one of the merged handlers. */
    private static boolean landed(ClassNode target, String selector, Set<String> merged) {
        if (merged.isEmpty()) {
            return false;
        }
        String name = selector;
        String desc = null;
        int semicolon = name.indexOf(';');
        int paren = name.indexOf('(');
        if (semicolon >= 0 && (paren < 0 || semicolon < paren) && name.startsWith("L")) {
            name = name.substring(semicolon + 1);
            paren = name.indexOf('(');
        }
        if (paren >= 0) {
            desc = name.substring(paren);
            name = name.substring(0, paren);
        }
        boolean prefix = name.endsWith("*");
        if (prefix) {
            name = name.substring(0, name.length() - 1);
        }
        // MixinExtras' @WrapMethod moves the original body into a method of its
        // own, and every other injector into that body goes with it.
        String wrapped = name + "$mixinextras$wrapped$";
        for (MethodNode method : target.methods) {
            if (merged.contains(method.name)) {
                continue;
            }
            boolean named = prefix ? method.name.startsWith(name)
                    : method.name.equals(name) || method.name.startsWith(wrapped);
            if (named && describes(desc, method, wrapped) && reaches(target, method, merged)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code method} is the one {@code desc} names. A body
     * {@code @WrapMethod} moved keeps its return type, but a {@code @Share}
     * appends parameters to it.
     */
    private static boolean describes(String desc, MethodNode method, String wrapped) {
        if (desc == null || desc.equals(method.desc)) {
            return true;
        }
        if (!method.name.startsWith(wrapped)) {
            return false;
        }
        Type named = Type.getMethodType(desc);
        Type actual = Type.getMethodType(method.desc);
        Type[] want = named.getArgumentTypes();
        Type[] have = actual.getArgumentTypes();
        if (!named.getReturnType().equals(actual.getReturnType()) || have.length < want.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            if (!want[i].equals(have[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code method} calls one of the merged handlers, itself or
     * through the bridges MixinExtras generates. When another mod wraps the
     * same call, the outer wrapper is handed an {@code Operation} whose body
     * -- a {@code mixinextras$bridge$} method -- is what calls the inner
     * handler, so the call is one step removed from the method it hooks.
     */
    private static boolean reaches(ClassNode target, MethodNode method, Set<String> merged) {
        java.util.ArrayDeque<MethodNode> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(method);
        seen.add(method.name + method.desc);
        while (!queue.isEmpty()) {
            MethodNode current = queue.poll();
            if (calls(current, target.name, merged)) {
                return true;
            }
            for (String bridge : bridgesFrom(current, target.name)) {
                for (MethodNode candidate : target.methods) {
                    if (candidate.name.equals(bridge) && seen.add(candidate.name + candidate.desc)) {
                        queue.add(candidate);
                    }
                }
            }
        }
        return false;
    }

    /** MixinExtras-generated methods of the same class that {@code method} calls or makes a handle to. */
    private static Set<String> bridgesFrom(MethodNode method, String owner) {
        Set<String> bridges = new HashSet<>();
        if (method.instructions == null) {
            return bridges;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals(owner) && isBridge(call.name)) {
                    bridges.add(call.name);
                }
            } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle handle && handle.getOwner().equals(owner) && isBridge(handle.getName())) {
                        bridges.add(handle.getName());
                    }
                }
            }
        }
        return bridges;
    }

    private static boolean isBridge(String name) {
        return name.startsWith("mixinextras$") || name.contains("$mixinextras$");
    }

    private static boolean calls(MethodNode method, String owner, Set<String> merged) {
        if (method.instructions == null) {
            return false;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals(owner) && merged.contains(call.name)) {
                    return true;
                }
            } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle handle && handle.getOwner().equals(owner)
                            && merged.contains(handle.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The other mixin configs that changed this class: the usual suspects when a hook is missing. */
    private static Set<String> otherMixins(String targetClassName, IMixinInfo self) {
        Set<String> others = new TreeSet<>();
        try {
            ClassInfo info = ClassInfo.forName(targetClassName.replace('.', '/'));
            if (info == null) {
                return others;
            }
            for (IMixinInfo applied : info.getAppliedMixins()) {
                if (applied.getConfig() != self.getConfig()) {
                    String source = applied.getConfig().getCleanSourceId();
                    others.add(source != null && !source.isEmpty()
                            ? source + " (" + applied.getConfig().getName() + ")"
                            : applied.getConfig().getName());
                }
            }
        } catch (Throwable unknown) {
            // Only a hint for the log.
        }
        return others;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
