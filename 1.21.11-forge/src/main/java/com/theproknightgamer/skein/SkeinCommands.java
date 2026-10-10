package com.theproknightgamer.skein;

import com.theproknightgamer.skein.core.Hooks;
import com.theproknightgamer.skein.core.DimensionTicker;
import com.theproknightgamer.skein.core.Demotions;
import com.theproknightgamer.skein.core.SkeinCompat;
import com.theproknightgamer.skein.core.SkeinPool;
import com.theproknightgamer.skein.core.TickMetrics;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /skein status} exists because the failure mode of a threading mod is
 * silence: it either helps, does nothing, or corrupts something, and from the
 * console all three look identical. The status readout is meant to distinguish
 * the first two -- an overlap factor near 1.00 means that phase is pure
 * overhead on this world and should be turned off.
 */
public final class SkeinCommands {
    private SkeinCommands() {}

    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("skein")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("unquarantine").executes(ctx -> unquarantine(ctx.getSource())));
        event.getDispatcher().register(root);
    }

    private static int status(CommandSourceStack source) {
        SkeinPool pool = Skein.pool();
        source.sendSuccess(() -> Component.literal("Skein").withStyle(ChatFormatting.AQUA), false);

        if (!SkeinConfig.Live.enabled) {
            source.sendSuccess(() -> line("state", Hooks.allows(Hooks.Feature.CORE)
                    ? "disabled (general.enabled=false)"
                    : "disabled: another mod changed code Skein depends on"), false);
            source.sendSuccess(() -> line("hooks", Hooks.summary()), false);
            return 1;
        }

        source.sendSuccess(() -> line("workers", pool == null ? "none" : String.valueOf(pool.size())), false);
        source.sendSuccess(() -> line("cells", SkeinConfig.Live.regionChunks + " chunks, "
                + SkeinConfig.Live.borderMargin + "-block margin"), false);
        source.sendSuccess(() -> line("phases", phases()), false);
        source.sendSuccess(() -> line("hooks", Hooks.summary()), false);
        for (Hooks.Missing hook : Hooks.missing()) {
            if (!hook.features().isEmpty()) {
                source.sendSuccess(() -> Component.literal("  missing hook: " + hook.describe())
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        }
        source.sendSuccess(() -> line("dimension overlap", String.format("%.2fx  (%.2fms level work in %.2fms wall)",
                TickMetrics.speedup(),
                TickMetrics.workNanos() / 1_000_000.0,
                TickMetrics.wallNanos() / 1_000_000.0)), false);

        for (TickMetrics.Phase phase : TickMetrics.Phase.values()) {
            TickMetrics.Stats stats = TickMetrics.of(phase);
            if (!stats.ran()) {
                continue;
            }
            source.sendSuccess(() -> line("  " + phase.label(), String.format(
                    "%.2fx  (%.2fms in %.2fms) %d cells over %d passes, %d parallel / %d serial, %d deferred",
                    stats.overlap(), stats.workMillis(), stats.wallMillis(),
                    stats.cells(), stats.passes(), stats.parallel(), stats.serial(), stats.deferred())), false);
        }

        if (TickMetrics.clusterEntities() > 0) {
            // Levels are the tier's serial depth, so entities/levels is the
            // width the pool actually had; a farm that levels badly says so
            // here rather than just running slowly.
            long levels = Math.max(1, TickMetrics.clusterLevels());
            source.sendSuccess(() -> line("  cluster tier", String.format(
                    "%d entities over %d levels (%.0f wide), %d slices, reach %.1f",
                    TickMetrics.clusterEntities(), TickMetrics.clusterLevels(),
                    TickMetrics.clusterEntities() / (double) levels,
                    TickMetrics.clusterTasks(), SkeinConfig.Live.clusterReach)), false);
        }

        if (SkeinConfig.Live.adaptive) {
            ServerLevel overworld = source.getServer().overworld();
            com.theproknightgamer.skein.core.PhaseGovernor governor =
                    ((com.theproknightgamer.skein.core.SkeinLevel) overworld).skein$governor();
            StringBuilder chosen = new StringBuilder();
            for (TickMetrics.Phase phase : TickMetrics.Phase.values()) {
                long par = governor.parallelNanos(phase);
                long ser = governor.serialNanos(phase);
                if (par == 0 && ser == 0) {
                    continue;
                }
                chosen.append(chosen.isEmpty() ? "" : ", ")
                        .append(phase.label()).append(' ')
                        .append(governor.parallel(phase) ? "parallel" : "serial")
                        .append(String.format(" (%.2f/%.2fms)", par / 1.0e6, ser / 1.0e6));
            }
            if (!chosen.isEmpty()) {
                source.sendSuccess(() -> line("  adaptive (overworld)", chosen.toString()), false);
            }
        }

        for (ServerLevel level : source.getServer().getAllLevels()) {
            ResourceKey<Level> key = level.dimension();
            double millis = TickMetrics.averageMillis(key);
            String suffix = DimensionTicker.quarantined().contains(key)
                    ? " (quarantined after a crash)"
                    : DimensionTicker.reachesAcross().contains(key)
                            ? " (ticks after the others: reached into another dimension)"
                            : SkeinConfig.Live.dimensionBlacklist.contains(key.identifier().toString())
                                    ? " (blacklisted)"
                                    : "";
            source.sendSuccess(() -> line("  " + key.identifier(), String.format("%.2fms%s", millis, suffix)), false);
        }

        java.util.List<String> demoted = Demotions.describe();
        if (!demoted.isEmpty()) {
            source.sendSuccess(() -> line("  on the level thread after failing in parallel", String.join(", ", demoted)),
                    false);
        }

        if (SkeinCompat.any()) {
            for (TickMetrics.Phase phase : TickMetrics.Phase.values()) {
                String reason = SkeinCompat.reason(phase);
                if (reason != null) {
                    source.sendSuccess(() -> Component.literal("  " + phase.label() + " is blocked: " + reason)
                            .withStyle(ChatFormatting.YELLOW), false);
                }
            }
        }

        long failures = TickMetrics.failureCount();
        if (failures > 0) {
            source.sendSuccess(() -> Component.literal("  " + failures + " tick failure(s) since start - see the log")
                    .withStyle(ChatFormatting.RED), false);
        }
        return 1;
    }

    private static String phases() {
        StringBuilder builder = new StringBuilder();
        append(builder, "dimensions", SkeinConfig.Live.dimensions, null);
        append(builder, "scheduledTicks", SkeinConfig.Live.scheduledTicks, TickMetrics.Phase.SCHEDULED_TICKS);
        append(builder, "randomTicks", SkeinConfig.Live.randomTicks, TickMetrics.Phase.RANDOM_TICKS);
        append(builder, "entities", SkeinConfig.Live.entities, TickMetrics.Phase.ENTITIES);
        append(builder, "blockEntities", SkeinConfig.Live.blockEntities, TickMetrics.Phase.BLOCK_ENTITIES);
        return builder.isEmpty() ? "none" : builder.toString();
    }

    private static void append(StringBuilder builder, String name, boolean on, TickMetrics.Phase phase) {
        if (!on) {
            return;
        }
        if (phase != null && !SkeinCompat.allows(phase)) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(name).append(" (blocked)");
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(", ");
        }
        builder.append(name);
    }

    /**
     * Only marks the config stale. A command can run from inside a level tick
     * -- a command block, a function -- while other dimensions are ticking,
     * and rebuilding the blacklists there would hand those levels an empty
     * list for a moment. The server thread applies it at the top of the next
     * tick, where nothing else is running; see {@link SkeinConfig#refreshIfStale}.
     */
    private static int reload(CommandSourceStack source) {
        SkeinConfig.markStale();
        source.sendSuccess(() -> Component.literal(
                "Skein: config will be re-read at the start of the next tick. Worker count changes apply on "
                        + "the next server start; ids that match nothing are listed in the log.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int unquarantine(CommandSourceStack source) {
        int count = DimensionTicker.quarantined().size() + DimensionTicker.reachesAcross().size();
        DimensionTicker.clearQuarantine();
        int types = Demotions.clear();
        source.sendSuccess(() -> Component.literal(
                "Skein: " + count + " dimension(s) and " + types + " demoted type(s) returned to parallel "
                        + "ticking; config/skein-demoted.txt is cleared.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static Component line(String key, String value) {
        return Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }
}
