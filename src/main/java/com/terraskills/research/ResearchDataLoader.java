package com.terraskills.research;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.terraskills.TerraSkills;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.Map;

/** Loads EVE research trees from the server datapack and refreshes them on /reload. */
@EventBusSubscriber(modid = TerraSkills.MOD_ID)
public final class ResearchDataLoader extends SimpleJsonResourceReloadListener {
    private static final ResearchDataLoader INSTANCE = new ResearchDataLoader();

    private ResearchDataLoader() { super(new Gson(), "terraskills_research"); }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) { event.addListener(INSTANCE); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        try {
            ResearchCatalog.replace(resources);
            TerraSkills.LOGGER.info("Loaded {} TerraSkills research trees from datapacks", ResearchCatalog.tracks().size());
        } catch (RuntimeException exception) {
            TerraSkills.LOGGER.error("Could not load TerraSkills research datapacks", exception);
            ResearchCatalog.replace(Map.of());
        }
    }
}
