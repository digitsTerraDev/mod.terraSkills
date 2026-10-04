package com.terraskills.research;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.terraskills.progression.RpgStat;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Server-reloadable catalogue backed by data/<namespace>/terraskills_research/*.json. */
public final class ResearchCatalog {
    private static volatile List<ResearchDefinition> definitions = List.of();
    private static volatile List<ResearchTree> trees = List.of();

    private ResearchCatalog() {}

    public static void replace(Map<ResourceLocation, JsonElement> resources) {
        List<ResearchTree> loadedTrees = new ArrayList<>();
        List<ResearchDefinition> loadedDefinitions = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))).forEach(entry -> {
            ResearchTree tree = parse(entry.getKey(), entry.getValue().getAsJsonObject());
            loadedTrees.add(tree);
            tree.skills().forEach(track -> loadedDefinitions.addAll(track.levels()));
        });
        definitions = List.copyOf(loadedDefinitions);
        trees = List.copyOf(loadedTrees);
    }

    private static ResearchTree parse(ResourceLocation category, JsonObject root) {
        String title = requiredString(root, "title", category);
        JsonArray skillArray = root.getAsJsonArray("skills");
        if (skillArray == null) throw new IllegalArgumentException(category + ": missing skills array");
        JsonObject requirements = root.getAsJsonObject("requires");
        List<ResearchTrack> skills = new ArrayList<>();
        for (JsonElement element : skillArray) skills.add(parseTrack(category, element.getAsJsonObject(), requirements));
        return new ResearchTree(category, title, skills);
    }

    private static ResearchTrack parseTrack(ResourceLocation category, JsonObject root, JsonObject treeRequirements) {
        String id = requiredString(root, "id", category), title = requiredString(root, "title", category), description = requiredString(root, "description", category);
        RpgStat primary = RpgStat.parse(requiredString(root, "primary_stat", category)).orElseThrow(() -> new IllegalArgumentException(category + ": invalid primary_stat"));
        RpgStat secondary = RpgStat.parse(requiredString(root, "secondary_stat", category)).orElseThrow(() -> new IllegalArgumentException(category + ": invalid secondary_stat"));
        List<String> bonuses = new ArrayList<>(); JsonArray bonusArray = root.getAsJsonArray("research_bonuses"); if (bonusArray != null) bonusArray.forEach(value -> bonuses.add(value.getAsString()));
        List<String> prerequisites = new ArrayList<>(); JsonArray prerequisiteArray = root.getAsJsonArray("requires"); if (prerequisiteArray == null && treeRequirements != null) prerequisiteArray = treeRequirements.getAsJsonArray(id); if (prerequisiteArray != null) prerequisiteArray.forEach(value -> prerequisites.add(value.getAsString()));
        JsonArray levelArray = root.getAsJsonArray("levels"); if (levelArray == null) throw new IllegalArgumentException(category + ": " + id + " missing levels array");
        List<ResearchDefinition> levels = new ArrayList<>();
        for (JsonElement element : levelArray) { JsonObject level = element.getAsJsonObject(); levels.add(new ResearchDefinition(category, requiredString(level, "skill", category), level.get("cost").getAsInt(), primary, secondary)); }
        return new ResearchTrack(category, id, title, description, bonuses, prerequisites, levels);
    }

    private static String requiredString(JsonObject object, String key, ResourceLocation id) {
        if (!object.has(key)) throw new IllegalArgumentException(id + ": missing " + key);
        return object.get(key).getAsString();
    }

    public static List<ResearchDefinition> all() { return definitions; }
    public static Optional<ResearchDefinition> find(ResourceLocation category, String skill) { return definitions.stream().filter(definition -> definition.category().equals(category) && definition.skill().equals(skill)).findFirst(); }
    public static List<ResearchTree> trees() { return trees; }
    public static List<ResearchTrack> tracks() { return trees.stream().flatMap(tree -> tree.skills().stream()).toList(); }
    public static Optional<ResearchTree> tree(ResourceLocation category) { return trees.stream().filter(tree -> tree.category().equals(category)).findFirst(); }
    public static Optional<ResearchTrack> track(ResourceLocation category, String skill) { return tracks().stream().filter(track -> track.category().equals(category) && track.levels().stream().anyMatch(level -> level.skill().equals(skill))).findFirst(); }
}
