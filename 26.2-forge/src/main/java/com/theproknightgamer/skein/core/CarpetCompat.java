package com.theproknightgamer.skein.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Carpet's explosion rule that changes what the ray loop reads.
 *
 * <p>Carpet redirects the ray loop's {@code getBlockState} in
 * {@code ServerExplosion.calculateExplodedPositions} through
 * {@code noBlockCalcsWithNoBLockDamage}, which hands back bedrock while
 * {@code explosionNoBlockDamage} is on and the real block otherwise. A redirect
 * inside the ray loop is another mod's code in the stretch Skein replaces, so
 * {@link Hooks} would leave the loop to carpet for good. It only matters while
 * the rule is on, though: off, the redirect is the call it replaced, and the
 * pool's rays read exactly what vanilla's would. So the loop is shared rather
 * than given up, and each explosion asks the rule before casting.
 *
 * <p>Ported from the 1.21.1 NeoForge line, where it was tested against
 * carpet's NeoForge port. No carpet build for this version was available to
 * check the handler's name against; without carpet nothing here does anything.
 */
public final class CarpetCompat {
    private CarpetCompat() {}

    /** The handler's name with and without the mod-id prefix newer Mixin adds. */
    public static boolean isBlockDamageRedirect(String handler) {
        return handler.equals("noBlockCalcsWithNoBLockDamage")
                || handler.endsWith("$noBlockCalcsWithNoBLockDamage");
    }

    // Resolved lazily, on the first explosion: carpet's settings class is
    // initialised by then, and absent carpet nothing is looked up again.
    private static final class Rule {
        static final VarHandle NO_BLOCK_DAMAGE = find();
        /** Carpet is here but its rule could not be read: never cast. */
        static final boolean UNREADABLE = NO_BLOCK_DAMAGE == null && present();

        private static boolean present() {
            try {
                Class.forName("carpet.CarpetSettings", false, CarpetCompat.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException absent) {
                return false;
            }
        }

        private static VarHandle find() {
            try {
                Class<?> settings = Class.forName("carpet.CarpetSettings", true, CarpetCompat.class.getClassLoader());
                return MethodHandles.publicLookup().findStaticVarHandle(settings, "explosionNoBlockDamage", boolean.class);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return null;
            }
        }
    }

    /** Whether carpet is currently making explosions read every block as bedrock. */
    public static boolean explosionNoBlockDamage() {
        VarHandle rule = Rule.NO_BLOCK_DAMAGE;
        return rule != null ? (boolean) rule.get() : Rule.UNREADABLE;
    }
}
