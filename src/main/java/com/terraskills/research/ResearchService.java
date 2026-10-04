package com.terraskills.research;

import com.terraskills.config.TerraSkillsConfig;
import com.terraskills.integration.NutritionSkillsBridge;
import com.terraskills.progression.PlayerProgress;
import com.terraskills.progression.ProgressionRuntime;
import com.terraskills.progression.SkillPointGeneration;
import com.terraskills.network.ResearchQueuePayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.puffish.skillsmod.api.Skill;
import net.puffish.skillsmod.api.SkillsAPI;

import java.util.ArrayList;
import java.util.List;

/** Owns all mutable research state. Pufferfish remains the prerequisite and reward authority. */
public final class ResearchService {
    private ResearchService() {}

    public static boolean enqueue(ServerPlayer player, ResourceLocation category, String skill) {
        ResearchDefinition definition = ResearchCatalog.find(category, skill).orElse(null);
        if (definition == null) return false;
        List<PlayerProgress.ResearchEntry> queue = new ArrayList<>(PlayerProgress.researchQueue(player));
        if (queue.stream().anyMatch(entry -> entry.category().equals(category) && entry.skill().equals(skill))) return false;
        if (!isResearchable(player, definition, queue)) return false;
        queue.add(new PlayerProgress.ResearchEntry(category, skill, 0));
        PlayerProgress.setResearchQueue(player, queue);
        return true;
    }

    /** Removes points issued by pre-research TerraSkills versions, so they cannot purchase nodes. */
    public static void clearLegacyNutritionPoints(ServerPlayer player) {
        ResearchCatalog.all().stream().map(ResearchDefinition::category).distinct()
                .forEach(categoryId -> SkillsAPI.getCategory(categoryId)
                        .ifPresent(category -> category.setPoints(player, NutritionSkillsBridge.NUTRITION_POINT_SOURCE, 0)));
    }

    public static boolean move(ServerPlayer player, int fromIndex, int toIndex) {
        List<PlayerProgress.ResearchEntry> queue = new ArrayList<>(PlayerProgress.researchQueue(player));
        if (fromIndex < 0 || fromIndex >= queue.size() || toIndex < 0 || toIndex >= queue.size()) return false;
        queue.add(toIndex, queue.remove(fromIndex));
        if (!isQueueOrderValid(player, queue)) return false;
        PlayerProgress.setResearchQueue(player, queue);
        return true;
    }

    public static boolean cancel(ServerPlayer player, int index) {
        List<PlayerProgress.ResearchEntry> queue = new ArrayList<>(PlayerProgress.researchQueue(player));
        if (index < 0 || index >= queue.size()) return false;
        queue.remove(index);
        // A later queued level is only valid while all of its prerequisite levels remain.
        // Reject the cancellation rather than leaving an impossible gap (for example I, III).
        if (!isQueueOrderValid(player, queue)) return false;
        PlayerProgress.setResearchQueue(player, queue);
        return true;
    }

    public static void advance(ServerPlayer player, long elapsedMillis) {
        if (elapsedMillis <= 0 || !ProgressionRuntime.isGenerating()) return;
        List<PlayerProgress.ResearchEntry> queue = new ArrayList<>(PlayerProgress.researchQueue(player));
        if (queue.isEmpty()) return;
        PlayerProgress.ResearchEntry active = queue.getFirst();
        ResearchDefinition definition = ResearchCatalog.find(active.category(), active.skill()).orElse(null);
        if (definition == null) {
            queue.removeFirst();
            PlayerProgress.setResearchQueue(player, queue);
            return;
        }
        double days = elapsedMillis / (TerraSkillsConfig.REAL_MINUTES_PER_DAY.get() * 60_000.0);
        double earned = SkillPointGeneration.calculate(player, definition.primaryStat(), definition.secondaryStat()).totalPerDay() * days;
        double total = active.points() + earned;
        if (total < definition.requiredPoints()) {
            queue.set(0, new PlayerProgress.ResearchEntry(active.category(), active.skill(), total));
            PlayerProgress.setResearchQueue(player, queue);
            return;
        }
        unlock(player, definition);
        queue.removeFirst();
        PlayerProgress.setResearchQueue(player, queue);
    }

    public static double currentRate(ServerPlayer player) {
        return PlayerProgress.researchQueue(player).stream().findFirst()
                .flatMap(entry -> ResearchCatalog.find(entry.category(), entry.skill()))
                .map(definition -> SkillPointGeneration.calculate(player, definition.primaryStat(), definition.secondaryStat()).totalPerDay())
                .orElse(0.0);
    }

    public static ResearchQueuePayload queueSnapshot(ServerPlayer player) {
        List<ResearchQueuePayload.Entry> entries = new ArrayList<>();
        List<ResearchQueuePayload.TrackProgress> tracks = new ArrayList<>();
        List<String> available = new ArrayList<>();
        long total = 0;
        for (PlayerProgress.ResearchEntry entry : PlayerProgress.researchQueue(player)) {
            ResearchDefinition definition = ResearchCatalog.find(entry.category(), entry.skill()).orElse(null);
            if (definition == null) continue;
            double rate = SkillPointGeneration.calculate(player, definition.primaryStat(), definition.secondaryStat()).totalPerDay();
            long remaining = rate <= 0 ? Long.MAX_VALUE : Math.max(0, Math.round((definition.requiredPoints() - entry.points()) / rate
                    * TerraSkillsConfig.REAL_MINUTES_PER_DAY.get() * 60_000.0));
            entries.add(new ResearchQueuePayload.Entry(entry.category().toString(), entry.skill(), entry.points(), definition.requiredPoints(), remaining));
            total = remaining == Long.MAX_VALUE || total > Long.MAX_VALUE - remaining ? Long.MAX_VALUE : total + remaining;
        }
        for (ResearchTrack track : ResearchCatalog.tracks()) {
            int completed = (int) track.levels().stream()
                    .takeWhile(level -> SkillsAPI.getCategory(level.category()).flatMap(category -> category.getSkill(level.skill()))
                            .map(skill -> skill.getState(player) == Skill.State.UNLOCKED).orElse(false))
                    .count();
            ResearchDefinition firstLevel = track.levels().getFirst();
            double rate = SkillPointGeneration.calculate(player, firstLevel.primaryStat(), firstLevel.secondaryStat()).totalPerDay();
            long millisPerPoint = rate <= 0 ? Long.MAX_VALUE : Math.max(1, Math.round(TerraSkillsConfig.REAL_MINUTES_PER_DAY.get() * 60_000.0 / rate));
            tracks.add(new ResearchQueuePayload.TrackProgress(track.category() + "/" + track.id(), completed, millisPerPoint));
        }
        for (ResearchDefinition definition : ResearchCatalog.all()) {
            SkillsAPI.getCategory(definition.category()).flatMap(category -> category.getSkill(definition.skill()))
                    .filter(skill -> skill.getState(player) == Skill.State.AVAILABLE || skill.getState(player) == Skill.State.AFFORDABLE)
                    .ifPresent(skill -> available.add(definition.id()));
        }
        return new ResearchQueuePayload(entries, tracks, List.copyOf(available), total);
    }

    private static boolean isResearchable(ServerPlayer player, ResearchDefinition definition, List<PlayerProgress.ResearchEntry> queue) {
        return ResearchCatalog.track(definition.category(), definition.skill()).map(track -> {
            int index = track.levels().indexOf(definition);
            if (!track.levels().subList(0, index).stream().allMatch(previous -> unlockedOrQueued(player, previous, queue))) return false;
            return index > 0 || track.prerequisites().stream()
                    .map(skill -> ResearchCatalog.find(track.category(), skill).orElse(null))
                    .allMatch(prerequisite -> prerequisite != null && unlockedOrQueued(player, prerequisite, queue));
        }).orElse(false);
    }

    private static boolean unlockedOrQueued(ServerPlayer player, ResearchDefinition definition, List<PlayerProgress.ResearchEntry> queue) {
        boolean queued = queue.stream().anyMatch(entry -> entry.category().equals(definition.category()) && entry.skill().equals(definition.skill()));
        boolean unlocked = SkillsAPI.getCategory(definition.category()).flatMap(category -> category.getSkill(definition.skill()))
                .map(skill -> skill.getState(player) == Skill.State.UNLOCKED).orElse(false);
        return queued || unlocked;
    }

    private static boolean isQueueOrderValid(ServerPlayer player, List<PlayerProgress.ResearchEntry> queue) {
        for (int queueIndex = 0; queueIndex < queue.size(); queueIndex++) {
            PlayerProgress.ResearchEntry entry = queue.get(queueIndex);
            ResearchDefinition definition = ResearchCatalog.find(entry.category(), entry.skill()).orElse(null);
            if (definition == null) return false;
            ResearchTrack track = ResearchCatalog.track(entry.category(), entry.skill()).orElse(null);
            if (track == null) return false;
            int level = track.levels().indexOf(definition);
            List<ResearchDefinition> requirements = new ArrayList<>(track.levels().subList(0, level));
            if (level == 0) track.prerequisites().forEach(skill -> ResearchCatalog.find(track.category(), skill).ifPresent(requirements::add));
            for (ResearchDefinition requirement : requirements) {
                boolean earlierInQueue = queue.subList(0, queueIndex).stream().anyMatch(previous -> previous.category().equals(requirement.category()) && previous.skill().equals(requirement.skill()));
                if (!earlierInQueue && !unlockedOrQueued(player, requirement, List.of())) return false;
            }
        }
        return true;
    }

    private static void unlock(ServerPlayer player, ResearchDefinition definition) {
        SkillsAPI.getCategory(definition.category()).flatMap(category -> category.getSkill(definition.skill()))
                .ifPresent(skill -> skill.unlock(player));
    }
}
