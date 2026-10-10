package com.theproknightgamer.skein.core;

/**
 * Implemented by {@code PersistentEntitySectionManager$Callback} so a parked
 * section move can tell whether the entity it belongs to has since been
 * removed.
 *
 * <p>Vanilla makes that impossible by construction: {@code onRemove} ends by
 * setting the entity's callback to {@code NULL}, so nothing that happens
 * afterwards can reach the callback again. Deferring the removal breaks that,
 * because the callback is still attached while the removal sits in a buffer --
 * and a move applied after a removal would put the entity <em>back</em> into a
 * section it has already been taken out of, leaving a discarded entity in the
 * spatial index.
 *
 * <p>So the detach happens immediately and only the teardown waits.
 */
public interface SkeinDetachable {

    /** True once this callback's removal has been parked and the entity detached. */
    boolean skein$detached();
}
