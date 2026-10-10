package com.theproknightgamer.skein.core;

import cpw.mods.modlauncher.api.INameMappingService;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/**
 * Method names for reflection. Forge 1.20.1 runs SRG names ({@code m_7892_}),
 * so a lookup by the name the source uses ({@code entityInside}) finds
 * nothing in a real server. Forge's own remapper turns the SRG id into
 * whatever the running game calls the method: the SRG id itself in
 * production, the readable name in a dev run.
 */
public final class Srg {
    private Srg() {}

    public static String method(String srg) {
        try {
            return ObfuscationReflectionHelper.remapName(INameMappingService.Domain.METHOD, srg);
        } catch (Throwable unavailable) {
            return srg;
        }
    }
}
