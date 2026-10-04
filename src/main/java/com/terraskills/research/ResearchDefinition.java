package com.terraskills.research;

import com.terraskills.progression.RpgStat;
import net.minecraft.resources.ResourceLocation;

/** Immutable, server-authoritative description of one Pufferfish node as research. */
public record ResearchDefinition(ResourceLocation category, String skill, int requiredPoints,
                                 RpgStat primaryStat, RpgStat secondaryStat) {
    public String id() {
        return category + "/" + skill;
    }
}
