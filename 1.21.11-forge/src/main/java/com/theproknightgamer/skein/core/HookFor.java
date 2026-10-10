package com.theproknightgamer.skein.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What an injection is part of, so that losing it costs only that.
 *
 * <p>Every injector in Skein is applied with {@code require = 0}, and
 * {@link Hooks} checks each one against the class it ended up in. An injector
 * that did not land -- almost always because another mod rewrote the same
 * method -- switches off the features named here and nothing else, instead of
 * stopping the game from loading. On a handler this names that handler's
 * features; on a mixin class it is the default for every handler in it. With
 * neither, the handler guards {@link Hooks.Feature#CORE}: all of Skein.
 *
 * <p>An empty list marks an injection that is an optimisation with no partner:
 * losing it changes nothing but speed, so it is only reported.
 *
 * <p>Naming a narrower feature is a claim that every other hook that depends
 * on this one is switched off by the same feature. Get it wrong and half a
 * mechanism runs; when in doubt, leave it unannotated.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface HookFor {
    Hooks.Feature[] value();

    /**
     * For a hook that takes a stretch of the method away from vanilla -- runs
     * it elsewhere, or not at all -- the handler whose call ends that stretch.
     * If another mod's injector lands inside it, or another mod overwrote the
     * method, that mod's change would be skipped for the work Skein takes, so
     * the features are switched off instead and the method runs as that mod
     * made it. The stretch ends at that call: an injector after it is not
     * seen, so the end marker should be the last thing the stretch covers.
     */
    String yieldsUntil() default "";

    /**
     * What yielding switches off, when that is less than what a missing hook
     * does -- the same hook can be optional (another mod may legitimately hold
     * the code it would take over) and still have something to give up when it
     * shares that code. Empty means {@link #value}.
     */
    Hooks.Feature[] yields() default {};
}
