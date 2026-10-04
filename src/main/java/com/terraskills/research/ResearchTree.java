package com.terraskills.research;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** A top-level EVE research tree containing one or more five-level skills. */
public record ResearchTree(ResourceLocation category, String title, List<ResearchTrack> skills) {
    public ResearchTree { skills = List.copyOf(skills); }
}
