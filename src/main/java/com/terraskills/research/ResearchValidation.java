package com.terraskills.research;

import com.terraskills.TerraSkills;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.puffish.skillsmod.api.SkillsAPI;

import java.util.ArrayList;
import java.util.List;

/** Validates the EVE-style five-level contract against the Pufferfish data loaded by the server. */
@EventBusSubscriber(modid = TerraSkills.MOD_ID)
public final class ResearchValidation {
    private static List<String> errors = List.of();

    private ResearchValidation() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        List<String> found = new ArrayList<>();
        for (ResearchTrack track : ResearchCatalog.tracks()) {
            var category = SkillsAPI.getCategory(track.category());
            if (category.isEmpty()) {
                found.add(track.title() + ": missing Pufferfish category " + track.category());
                continue;
            }
            if (track.levels().size() != 5) {
                found.add(track.title() + ": expected exactly 5 levels but found " + track.levels().size());
            }
            for (ResearchDefinition level : track.levels()) {
                if (category.get().getSkill(level.skill()).isEmpty()) {
                    found.add(track.title() + ": missing level " + level.skill());
                }
            }
        }
        errors = List.copyOf(found);
        for (String error : errors) TerraSkills.LOGGER.error("Research configuration error: {}", error);
        event.getServer().getPlayerList().getPlayers().forEach(ResearchValidation::notify);
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) notify(player);
    }

    private static void notify(ServerPlayer player) {
        for (String error : errors) player.sendSystemMessage(Component.literal("[TerraSkills] Research configuration error: " + error)
                .withStyle(ChatFormatting.RED));
    }
}
