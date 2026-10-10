package com.theproknightgamer.skein;

import com.theproknightgamer.skein.core.Hooks;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Adds the compatibility mixins for whichever other mods are actually present.
 *
 * <p>A mixin declared against another mod's class cannot simply be listed in
 * {@code skein.mixins.json}: with that mod absent the target does not exist and
 * Mixin fails while preparing the config, taking the whole mod down on every
 * server that does not happen to run it. {@link #getMixins()} is the supported
 * way round that -- the mixin is added to the config only once the mod is known
 * to be installed, so within the compat mixins the target is always there. If
 * the mod's internals move, {@link Hooks} finds the injector missing and
 * switches off the in-level phases the patch protects.
 *
 * <p>Detection is by mod id against FML's loading list rather than by
 * {@code Class.forName}: mixin configs are prepared before most mod classes are
 * loadable, and asking for one early can pull it through the wrong class loader.
 */
public final class SkeinMixinPlugin implements IMixinConfigPlugin {

    /**
     * SpeeDeeEntity caches collision clearance in an unsynchronised
     * {@code Long2ByteOpenHashMap} on the level; see
     * {@link com.theproknightgamer.skein.core.SpeeDeeCompat}.
     */
    private static final String SPEEDEE_ENTITY = "speedeeentity";

    private String mixinPackage = "com.theproknightgamer.skein.mixin";

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        Hooks.expect(myTargets);
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // 1.20.1 has no Lithium for Forge. Its ports, Canary and Radium, have
        // no chunk-status tracker to patch, but both merge Lithium's
        // block-entity lookup, which answers only on Level.thread -- which a
        // dimension ticking on a worker is not.
        if ((installed("canary") && containsClass("canary",
                "com/abdelaziz/canary/mixin/util/block_entity_retrieval/LevelMixin.class"))
                || (installed("radium") && containsClass("radium",
                "me/jellysquid/mods/lithium/mixin/util/block_entity_retrieval/WorldMixin.class"))) {
            mixins.add("compat.LithiumBlockEntityRetrievalMixin");
        }
        // One TickHandler for the server, ticked from every level's tick.
        if (installed("neruina")) {
            mixins.add("compat.NeruinaTickHandlerMixin");
        }
        // A per-level set of chunks to force-load, filled from projectile ticks.
        if (installed("ritchiesprojectilelib")) {
            mixins.add("compat.RplChunkManagerMixin");
        }
        // A static map of entities a spell just hit, filled from spell ticks.
        if (installed("irons_spellbooks")) {
            mixins.add("compat.IronsKnockbackMixin");
        }
        // Kinetic networks, tunnels, links and logistics; see CreateCompat.
        if (installed("create") && containsClass("create",
                "com/simibubi/create/content/kinetics/TorquePropagator.class")) {
            mixins.addAll(List.of("compat.CreateKineticNetworkMixin", "compat.CreateTorquePropagatorMixin",
                    "compat.CreateRotationMixin", "compat.CreateGeneratorMixin", "compat.CreateBrassTunnelMixin",
                    "compat.CreateRedstoneLinkMixin", "compat.CreateFrequencyMixin", "compat.CreateDeployerMixin"));
            // Create 6 only; 0.5.1 has neither.
            if (containsClass("create", "com/simibubi/create/foundation/utility/TickBasedCache.class")) {
                mixins.add("compat.CreateTickBasedCacheMixin");
            }
            if (containsClass("create",
                    "com/simibubi/create/content/logistics/packagerLink/GlobalLogisticsManager.class")) {
                mixins.add("compat.CreateLogisticsMixin");
            }
        }
        if (installed(SPEEDEE_ENTITY)) {
            mixins.addAll(List.of("compat.OFEMUtilMixin",
                    "compat.SpeeDeeTickCacheMixin", "compat.SpeeDeeLevelMixin", "compat.SpeeDeeBlockChangeMixin"));
            // Some 1.0.1 jars contain the newer index too. Inspect the mod's file
            // rather than guessing from its version or loading classes this early.
            boolean indexed = containsClass(SPEEDEE_ENTITY,
                    "com/theproknightgamer/speedeeentity/ofem/EntityColliders.class");
            if (indexed) {
                mixins.add("compat.SpeeDeeEntityCollidersMixin");
                mixins.add("compat.SpeeDeeEpochAccessor");
            }
            // Worth a line in the log: this silently changes how another mod's
            // optimisation stores its answers, and an operator chasing a collision
            // bug should be able to see that it happened.
            org.apache.logging.log4j.LogManager.getLogger("Skein").info(
                    "Skein: SpeeDeeEntity found; per-thread clearance caches{}, with block-change invalidation",
                    indexed ? " and a shared per-tick collider index" : "");
        }
        Hooks.expectMixins(this.mixinPackage, mixins);
        return mixins;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /**
     * Every injector here is applied with {@code require = 0}; {@link Hooks}
     * checks each one instead, once the class is finished.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        try {
            Hooks.applied(targetClassName, targetClass, mixinClassName, mixinInfo);
        } catch (Throwable unexpected) {
            Hooks.unverifiable(targetClassName, mixinClassName, unexpected);
        }
    }

    /** Whether the mod's own file holds a class, read without loading it. */
    private static boolean containsClass(String modId, String path) {
        try {
            return Files.exists(net.minecraftforge.fml.loading.LoadingModList.get()
                    .getModFileById(modId).getFile().findResource(path));
        } catch (Throwable unreadable) {
            return false;
        }
    }

    private static boolean installed(String modId) {
        try {
            return net.minecraftforge.fml.loading.LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable notAvailable) {
            // Nothing to fall back to: guessing "present" would break every
            // server without the mod, and guessing "absent" would leave the
            // race unpatched on the ones with it. Say so rather than pick.
            System.err.println("[Skein] could not read the mod list while preparing mixins; "
                    + "compatibility mixin for " + modId + " not applied: " + notAvailable);
            return false;
        }
    }
}
