package com.terraskills.progression;

import com.terraskills.TerraSkills;
import com.terraskills.config.TerraSkillsConfig;
import com.terraskills.network.SkillProgressPayload;
import com.terraskills.research.ResearchService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = TerraSkills.MOD_ID)
public final class ProgressionEvents {
    private ProgressionEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ResearchService.clearLegacyNutritionPoints(player);
            if (!TerraSkillsConfig.ACCRUE_WHILE_OFFLINE.get()) {
                PlayerProgress.setLastRealTimeMillis(player, System.currentTimeMillis());
            }
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer oldPlayer && event.getEntity() instanceof ServerPlayer newPlayer) {
            PlayerProgress.copy(oldPlayer, newPlayer);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 20 != 0) return;
        long now = System.currentTimeMillis();
        long previous = PlayerProgress.lastRealTimeMillis(player);
        PlayerProgress.setLastRealTimeMillis(player, now);
        if (previous <= 0 || now <= previous) return;

        // Updating the timestamp before this check means paused time is never paid out later.
        if (!ProgressionRuntime.isGenerating()) {
            syncHud(player);
            return;
        }

        ResearchService.advance(player, now - previous);
        syncHud(player);
    }

    private static void syncHud(ServerPlayer player) {
        SkillPointGeneration.NutritionBreakdown nutrition = SkillPointGeneration.calculateNutrition(player);
        double maximumNutrition = TerraSkillsConfig.GRAIN_POINTS.get() + TerraSkillsConfig.FRUIT_POINTS.get()
                + TerraSkillsConfig.VEGETABLE_POINTS.get() + TerraSkillsConfig.PROTEIN_POINTS.get()
                + TerraSkillsConfig.DAIRY_POINTS.get();
        PlayerProgress.researchQueue(player).stream().findFirst().ifPresentOrElse(research -> {
            var definition = com.terraskills.research.ResearchCatalog.find(research.category(), research.skill()).orElse(null);
            if (definition == null) return;
            double progress = Math.clamp(research.points() / definition.requiredPoints(), 0, 1);
            double rate = ResearchService.currentRate(player);
            PacketDistributor.sendToPlayer(player, new SkillProgressPayload(definition.id(), progress, rate,
                    TerraSkillsConfig.REAL_MINUTES_PER_DAY.get() * 60_000.0, ProgressionRuntime.isGenerating(),
                    nutrition.beforeBalance(), maximumNutrition, nutrition.grain().fullness(), nutrition.fruit().fullness(),
                    nutrition.vegetables().fullness(), nutrition.protein().fullness(), nutrition.dairy().fullness()));
        }, () -> PacketDistributor.sendToPlayer(player,
                new SkillProgressPayload("", 0, 0, TerraSkillsConfig.REAL_MINUTES_PER_DAY.get() * 60_000.0,
                        ProgressionRuntime.isGenerating(), nutrition.beforeBalance(), maximumNutrition,
                        nutrition.grain().fullness(), nutrition.fruit().fullness(), nutrition.vegetables().fullness(),
                        nutrition.protein().fullness(), nutrition.dairy().fullness())));
        PacketDistributor.sendToPlayer(player, ResearchService.queueSnapshot(player));
    }
}
