package com.theproknightgamer.skein.core;

import com.theproknightgamer.skein.SkeinConfig;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Entity-collision queries that look only at entities that can be collided with.
 *
 * <p>Every entity's move asks the level for the entities its path could bump
 * into ({@code getEntityCollisions}), and vanilla answers by testing every
 * entity in the box. Almost nothing can be collided with -- in vanilla only a
 * boat, a shulker or a happy ghast -- so on an ordinary server that test fails
 * a few times and nobody notices. A cannon's TNT duper is not ordinary: it piles
 * thousands of primed TNT on exactly the same spot, and every piston that pushes
 * the pile moves each TNT in turn, each one asking about all the others. One
 * push of a stack of N costs N x N tests: twenty-five million for a 5,000-TNT
 * payload, measured at nearly half of the server thread while a cannon built,
 * in single ticks of over a second when the stack itself moved.
 *
 * <p>Lithium answers it with per-class entity lists in each section, built on the
 * first query. Skein has to switch those off -- two workers making the first
 * query of one section at once corrupt the map -- and so took the fix away with
 * them. This is the thread-safe equivalent: each entity section also keeps the
 * list of its members that could ever be collided with (or that carry parts that
 * could), maintained as members come and go, and a collision query walks that
 * list instead of the whole section. The query's own predicate still decides, so
 * the answer is the one vanilla gives, in the same order: everything left out
 * would have failed it.
 *
 * <p>"Could ever be collided with" is a property of the class: one that keeps
 * {@code Entity.canBeCollidedWith} always says no. It only holds while the
 * asker keeps {@code Entity.canCollideWith} -- a boat or minecart also collides
 * with anything pushable -- so their queries take the full walk. And it only
 * holds while no other mod has changed those two methods on {@code Entity}
 * itself; if one has, the feature switches off ({@code EntityCollisionRuleMixin}).
 */
public final class CollisionQueries {
    private CollisionQueries() {}

    /** Instances may answer yes to {@code canBeCollidedWith}, or hold parts that may. */
    private static final ClassValue<Boolean> CANDIDATE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declaresBelowEntity(type, "canBeCollidedWith", Entity.class)
                    || declaresBelowEntity(type, "isMultipartEntity");
        }
    };

    /** Asks with {@code Entity}'s own {@code canCollideWith}. */
    private static final ClassValue<Boolean> PLAIN_ASKER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return !declaresBelowEntity(type, "canCollideWith", Entity.class);
        }
    };

    /**
     * Whether a class between {@code type} and {@code Entity} declares the method.
     * Unreadable classes (a signature naming a class this side cannot load) count
     * as declaring it, which is the answer that leaves the query alone.
     */
    private static boolean declaresBelowEntity(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null && c != Entity.class && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(name, params);
                return true;
            } catch (NoSuchMethodException absent) {
                // keep looking up
            } catch (LinkageError | TypeNotPresentException | SecurityException unreadable) {
                return true;
            }
        }
        return false;
    }

    /** Whether an entity section keeps this member on its collider list. */
    public static boolean candidate(EntityAccess member) {
        return !(member instanceof Entity) || CANDIDATE.get(member.getClass());
    }

    /**
     * The predicate for a collision query from {@code source}, marked when the
     * section lists may answer it.
     */
    public static Predicate<? super Entity> mark(@Nullable Entity source, Predicate<? super Entity> original) {
        if (SkeinConfig.Live.collisionIndex && (source == null || PLAIN_ASKER.get(source.getClass()))) {
            return new Filter(original);
        }
        return original;
    }

    /** A collision query's own predicate, recognisable on its way down. */
    public record Filter(Predicate<? super Entity> original) implements Predicate<Entity> {
        @Override
        public boolean test(Entity entity) {
            return this.original.test(entity);
        }
    }

    /** Carries the marker through Level's list-building consumer. */
    public record Candidates<T>(Consumer<T> original) implements Consumer<T> {
        @Override
        public void accept(T entity) {
            this.original.accept(entity);
        }
    }

    /** Same behaviour as {@code AbortableIterationConsumer.forConsumer}, with a type a section can see. */
    public record Targets<T>(Consumer<T> original) implements AbortableIterationConsumer<T> {
        @Override
        public Continuation accept(T entity) {
            this.original.accept(entity);
            return Continuation.CONTINUE;
        }
    }
}
