package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityAccess;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Per-query markers, never ambient thread flags: nested unrelated queries stay vanilla. */
public final class ProjectileQueries {
    private ProjectileQueries() {}

    /** Exact vanilla classes only. Subclasses may change either side of collision eligibility. */
    public static boolean ordinaryThrowable(EntityAccess entity) {
        Class<?> type = entity.getClass();
        return type == Snowball.class || type == ThrownEgg.class;
    }

    /**
     * Marks the hit query of a vanilla snowball or egg, so the entity sections
     * it walks skip other vanilla snowballs and eggs -- which its own
     * {@code canHitEntity} would reject anyway.
     *
     * <p>On the server this applies inside Skein's deferred passes. On the
     * client it applies always: the client ticks every snowball too, running
     * the same hit query against the same sections, and a 1,880-snowball
     * column made that a 3.5-million-candidate scan every client tick -- nine
     * tenths of the render thread, for hits the client never acts on. The
     * client ticks entities on one thread and edits sections in place, which
     * the per-section list's invalidation already covers.
     */
    public static Predicate<Entity> mark(Entity projectile, Predicate<Entity> original) {
        Level level = projectile.level();
        if (SkeinConfig.Live.enabled && SkeinConfig.Live.threadedProjectileQueries && ordinaryThrowable(projectile)
                && (level.isClientSide() || TickContext.deferring() && TickContext.owns(level))) {
            return new Filter(level, original);
        }
        return original;
    }

    /** Delegates every remaining target decision to the original canHitEntity predicate. */
    public record Filter(Level level, Predicate<Entity> original) implements Predicate<Entity> {
        @Override public boolean test(Entity entity) { return original.test(entity); }
    }

    /** Carries the marker through Level's normal exclusion/filter/list-building consumer. */
    public record Candidates<T>(Consumer<T> original) implements Consumer<T> {
        @Override public void accept(T entity) { original.accept(entity); }
    }

    /** Same behavior as AbortableIterationConsumer.forConsumer, with a recognizable type. */
    public record Targets<T>(Consumer<T> original) implements AbortableIterationConsumer<T> {
        @Override public Continuation accept(T entity) {
            original.accept(entity);
            return Continuation.CONTINUE;
        }
    }
}
