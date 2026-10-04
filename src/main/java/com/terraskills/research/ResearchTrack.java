package com.terraskills.research;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** One EVE-style trainable skill with five sequential Pufferfish levels. */
public record ResearchTrack(ResourceLocation category, String id, String title, String description,
                            List<String> researchBonuses, List<String> prerequisites, List<ResearchDefinition> levels) {
    public ResearchTrack {
        researchBonuses = List.copyOf(researchBonuses);
        prerequisites = List.copyOf(prerequisites);
        levels = List.copyOf(levels);
    }
}
