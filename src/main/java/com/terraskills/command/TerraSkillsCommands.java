package com.terraskills.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.terraskills.TerraSkills;
import com.terraskills.config.TerraSkillsConfig;
import com.terraskills.integration.NutritionSkillsBridge;
import com.terraskills.progression.PlayerProgress;
import com.terraskills.progression.ProgressionRuntime;
import com.terraskills.progression.RpgStat;
import com.terraskills.progression.SkillPointGeneration;
import com.terraskills.progression.SkillTreeDefinition;
import com.terraskills.research.ResearchCatalog;
import com.terraskills.research.ResearchService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.terraskills.network.ResearchQueuePayload;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.List;

@EventBusSubscriber(modid = TerraSkills.MOD_ID)
public final class TerraSkillsCommands {
    private TerraSkillsCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(createRoot("terraskills"));
        event.getDispatcher().register(createRoot("ts"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRoot(String name) {
        var root = Commands.literal(name).executes(context -> help(context.getSource()));
        root.then(Commands.literal("help").executes(context -> help(context.getSource())));
        root.then(Commands.literal("info").executes(context -> info(context.getSource().getPlayerOrException())));
        root.then(Commands.literal("next").executes(context -> next(context.getSource().getPlayerOrException())));
        root.then(Commands.literal("nutrition").executes(context -> nutrition(context.getSource().getPlayerOrException())));
        root.then(Commands.literal("messages")
                .executes(context -> messageStatus(context.getSource().getPlayerOrException()))
                .then(Commands.literal("status").executes(context -> messageStatus(context.getSource().getPlayerOrException())))
                .then(Commands.literal("enable").executes(context -> setMessages(context.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("disable").executes(context -> setMessages(context.getSource().getPlayerOrException(), false))));
        root.then(Commands.literal("tree")
                .then(Commands.literal("list").executes(context -> list(context.getSource().getPlayerOrException())))
                .then(Commands.literal("select").then(Commands.argument("tree", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            SkillTreeDefinition.configuredTrees().forEach(tree -> {
                                builder.suggest(tree.id().getPath());
                                builder.suggest(tree.id().toString());
                            });
                            return builder.buildFuture();
                        })
                        .executes(context -> select(context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "tree"))))));
        root.then(Commands.literal("research")
                .then(Commands.literal("list").executes(context -> researchList(context.getSource().getPlayerOrException())))
                .then(Commands.literal("start").then(Commands.argument("research", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            ResearchCatalog.all().forEach(definition -> builder.suggest(definition.id()));
                            return builder.buildFuture();
                        })
                        .executes(context -> researchStart(context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "research")))))
                .then(Commands.literal("move").then(Commands.argument("from", IntegerArgumentType.integer(1))
                        .then(Commands.argument("to", IntegerArgumentType.integer(1))
                                .executes(context -> researchMove(context.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(context, "from"), IntegerArgumentType.getInteger(context, "to"))))))
                .then(Commands.literal("cancel").then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(context -> researchCancel(context.getSource().getPlayerOrException(),
                                IntegerArgumentType.getInteger(context, "index"))))));
        root.then(Commands.literal("stat").requires(source -> source.hasPermission(2))
                .then(Commands.literal("set").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("stat", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (RpgStat stat : RpgStat.values()) builder.suggest(stat.id());
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(context -> setStat(EntityArgument.getPlayers(context, "targets"),
                                                StringArgumentType.getString(context, "stat"),
                                                IntegerArgumentType.getInteger(context, "value"))))))));
        root.then(Commands.literal("generation")
                .then(Commands.literal("status").executes(context -> generationStatus(context.getSource())))
                .then(Commands.literal("pause").requires(source -> source.hasPermission(2)).executes(context -> {
                    ProgressionRuntime.pause();
                    context.getSource().sendSuccess(() -> success("Generation paused. No progress will accrue."), true);
                    return 1;
                }))
                .then(Commands.literal("resume").requires(source -> source.hasPermission(2)).executes(context -> {
                    ProgressionRuntime.resume();
                    context.getSource().sendSuccess(() -> ProgressionRuntime.isGenerating()
                            ? success("Generation resumed.")
                            : warning(
                            ProgressionRuntime.isGenerating()
                                    ? "Generation resumed."
                                    : "Runtime pause cleared, but generation.enabled is false in the server config."), true);
                    return 1;
                })));
        return root;
    }

    private static int researchList(ServerPlayer player) {
        var queue = PlayerProgress.researchQueue(player);
        if (queue.isEmpty()) {
            player.sendSystemMessage(Component.literal("Research queue is empty.").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        player.sendSystemMessage(Component.literal("RESEARCH QUEUE").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
        for (int index = 0; index < queue.size(); index++) {
            var entry = queue.get(index);
            var definition = ResearchCatalog.find(entry.category(), entry.skill()).orElse(null);
            if (definition == null) continue;
            String state = String.format(java.util.Locale.ROOT, "%.1f / %d", entry.points(), definition.requiredPoints());
            player.sendSystemMessage(Component.literal("  " + (index + 1) + ". " + definition.id() + " — " + state)
                    .withStyle(index == 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int researchStart(ServerPlayer player, String id) {
        int separator = id.lastIndexOf('/');
        ResourceLocation category = separator < 1 ? null : ResourceLocation.tryParse(id.substring(0, separator));
        boolean queued = category != null && ResearchService.enqueue(player, category, id.substring(separator + 1));
        syncResearchQueue(player);
        player.sendSystemMessage(Component.literal(queued ? "Research queued: " + id : "That research is unavailable or already queued.")
                .withStyle(queued ? ChatFormatting.GREEN : ChatFormatting.RED));
        return queued ? 1 : 0;
    }

    private static int researchMove(ServerPlayer player, int from, int to) {
        boolean moved = ResearchService.move(player, from - 1, to - 1);
        syncResearchQueue(player);
        player.sendSystemMessage(Component.literal(moved ? "Research queue reordered." : "Invalid queue positions.")
                .withStyle(moved ? ChatFormatting.GREEN : ChatFormatting.RED));
        return moved ? 1 : 0;
    }

    private static int researchCancel(ServerPlayer player, int index) {
        boolean cancelled = ResearchService.cancel(player, index - 1);
        syncResearchQueue(player);
        player.sendSystemMessage(Component.literal(cancelled ? "Research cancelled; saved progress was discarded." : "Invalid queue position.")
                .withStyle(cancelled ? ChatFormatting.YELLOW : ChatFormatting.RED));
        return cancelled ? 1 : 0;
    }

    /** Commands mutate the queue between normal tick snapshots, so update the open terminal immediately. */
    private static void syncResearchQueue(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, ResearchService.queueSnapshot(player));
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> header("COMMAND HELP"), false);
        helpLine(source, "/ts info", "Show your active tree, RPG stats, rate, and saved progress.");
        helpLine(source, "/ts nutrition", "Explain your nutrition contributions and balance modifier.");
        helpLine(source, "/ts messages enable|disable", "Control nutrition updates shown after eating.");
        helpLine(source, "/ts next", "Show when and where your next skill point will arrive.");
        helpLine(source, "/ts tree list", "List every tree, its points, rate, progress, and next point.");
        helpLine(source, "/ts tree select <tree>", "Pause the old tree and begin training another.");
        helpLine(source, "/ts generation status", "Check whether passive generation is running.");
        if (source.hasPermission(2)) {
            source.sendSuccess(() -> Component.literal("  ADMIN COMMANDS").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD), false);
            helpLine(source, "/ts generation pause", "Pause skill-point generation server-wide.");
            helpLine(source, "/ts generation resume", "Resume skill-point generation server-wide.");
            helpLine(source, "/ts stat set <players> <stat> <value>", "Set a player's RPG stat.");
        }
        source.sendSuccess(TerraSkillsCommands::footer, false);
        return 1;
    }

    private static void helpLine(CommandSourceStack source, String command, String description) {
        source.sendSuccess(() -> Component.literal("  ◆ ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(command).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\n     " + description).withStyle(ChatFormatting.GRAY)), false);
    }

    private static int generationStatus(CommandSourceStack source) {
        source.sendSuccess(() -> header("GENERATION STATUS"), false);
        source.sendSuccess(() -> field("State", ProgressionRuntime.isGenerating() ? "RUNNING" : "PAUSED / DISABLED",
                ProgressionRuntime.isGenerating() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        source.sendSuccess(() -> field("Runtime paused", yesNo(ProgressionRuntime.isPaused()),
                ProgressionRuntime.isPaused() ? ChatFormatting.RED : ChatFormatting.GREEN), false);
        source.sendSuccess(TerraSkillsCommands::footer, false);
        return 1;
    }

    private static int messageStatus(ServerPlayer player) {
        boolean enabled = PlayerProgress.nutritionMessagesEnabled(player);
        player.sendSystemMessage(field("Nutrition messages", enabled ? "ENABLED" : "DISABLED",
                enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
        return 1;
    }

    private static int setMessages(ServerPlayer player, boolean enabled) {
        PlayerProgress.setNutritionMessagesEnabled(player, enabled);
        player.sendSystemMessage(success("Nutrition messages " + (enabled ? "enabled." : "disabled.")));
        return 1;
    }

    private static int select(ServerPlayer player, String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        Optional<SkillTreeDefinition> tree = id == null ? Optional.empty() : SkillTreeDefinition.find(id);
        if (tree.isEmpty() && !value.contains(":")) {
            List<SkillTreeDefinition> shortMatches = SkillTreeDefinition.configuredTrees().stream()
                    .filter(candidate -> candidate.id().getPath().equals(value))
                    .toList();
            if (shortMatches.size() == 1) {
                tree = Optional.of(shortMatches.getFirst());
            } else if (shortMatches.size() > 1) {
                player.sendSystemMessage(error("Ambiguous skill tree name: " + value));
                player.sendSystemMessage(Component.literal("  Use a full ID: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(shortMatches.stream().map(match -> match.id().toString())
                                .collect(java.util.stream.Collectors.joining(", "))).withStyle(ChatFormatting.AQUA)));
                return 0;
            }
        }
        if (tree.isEmpty()) {
            player.sendSystemMessage(error("Unknown configured skill tree: " + value));
            return 0;
        }
        PlayerProgress.selectTree(player, tree.get().id());
        player.sendSystemMessage(header("TRAINING UPDATED"));
        player.sendSystemMessage(field("Active tree", tree.get().id().toString(), ChatFormatting.AQUA));
        player.sendSystemMessage(field("Primary", title(tree.get().primary().id()), ChatFormatting.GOLD));
        player.sendSystemMessage(field("Secondary", title(tree.get().secondary().id()), ChatFormatting.YELLOW));
        player.sendSystemMessage(field("Saved progress", formatProgress(player, tree.get()), ChatFormatting.GREEN));
        player.sendSystemMessage(Component.literal("  Previous tree progress remains saved and paused.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        player.sendSystemMessage(footer());
        return 1;
    }

    private static int list(ServerPlayer player) {
        player.sendSystemMessage(header("AVAILABLE SKILL TREES"));
        Optional<ResourceLocation> selected = PlayerProgress.selectedTree(player);
        SkillTreeDefinition.configuredTrees().forEach(tree -> {
            boolean active = selected.filter(tree.id()::equals).isPresent();
            SkillPointGeneration.Breakdown rate = SkillPointGeneration.calculate(player, tree);
            OptionalInt points = NutritionSkillsBridge.getAvailablePoints(player, tree.id());
            player.sendSystemMessage(Component.literal(active ? "  ▶ " : "  ◆ ")
                        .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.DARK_AQUA)
                        .append(Component.literal(tree.id().toString()).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(active ? "  ACTIVE" : "  PAUSED").withStyle(
                                active ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                        .append(Component.literal("  P: ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(title(tree.primary().id())).withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("  S: ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(title(tree.secondary().id())).withStyle(ChatFormatting.YELLOW)));
            player.sendSystemMessage(Component.literal("      Points: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(points.isPresent() ? Integer.toString(points.getAsInt()) : "category unavailable")
                            .withStyle(points.isPresent() ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .append(Component.literal("  Saved: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(formatProgress(player, tree)).withStyle(ChatFormatting.AQUA)));
            player.sendSystemMessage(Component.literal("      Rate: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.format("%.3f/day", rate.totalPerDay())).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("  Next: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(timeUntilNext(player, tree, rate.totalPerDay(), active)).withStyle(
                            active ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY)));
        });
        player.sendSystemMessage(footer());
        return 1;
    }

    private static int next(ServerPlayer player) {
        player.sendSystemMessage(header("NEXT SKILL POINT"));
        Optional<SkillTreeDefinition> tree = PlayerProgress.selectedTree(player).flatMap(SkillTreeDefinition::find);
        if (tree.isEmpty()) {
            player.sendSystemMessage(warning("No active tree. Choose one with /ts tree select <tree>."));
            player.sendSystemMessage(footer());
            return 0;
        }
        SkillPointGeneration.Breakdown rate = SkillPointGeneration.calculate(player, tree.get());
        player.sendSystemMessage(field("Tree", tree.get().id().toString(), ChatFormatting.AQUA));
        player.sendSystemMessage(field("Saved progress", formatProgress(player, tree.get()), ChatFormatting.GREEN));
        player.sendSystemMessage(field("Current rate", String.format("%.3f points/day", rate.totalPerDay()), ChatFormatting.GREEN));
        player.sendSystemMessage(field("Time remaining", timeUntilNext(player, tree.get(), rate.totalPerDay(), true), ChatFormatting.YELLOW));
        player.sendSystemMessage(footer());
        return 1;
    }

    private static int nutrition(ServerPlayer player) {
        SkillPointGeneration.NutritionBreakdown value = SkillPointGeneration.calculateNutrition(player);
        player.sendSystemMessage(header("NUTRITION MODIFIER"));
        player.sendSystemMessage(nutrientLine("Grain", value.grain()));
        player.sendSystemMessage(nutrientLine("Fruit", value.fruit()));
        player.sendSystemMessage(nutrientLine("Vegetables", value.vegetables()));
        player.sendSystemMessage(nutrientLine("Protein", value.protein()));
        player.sendSystemMessage(nutrientLine("Dairy", value.dairy()));
        player.sendSystemMessage(Component.literal("  BALANCE").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
        player.sendSystemMessage(field("Average closeness", String.format("%.1f%%", value.balanceScore() * 100), ChatFormatting.YELLOW));
        player.sendSystemMessage(field("Balance multiplier", String.format("× %.3f", value.balanceMultiplier()),
                value.balanceMultiplier() >= 0.9 ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("  SKILL POINT EFFECT").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
        player.sendSystemMessage(field("Nutrition before balance", String.format("%.3f points/day", value.beforeBalance()), ChatFormatting.WHITE));
        player.sendSystemMessage(field("Nutrition after balance", String.format("%.3f points/day", value.afterBalance()), ChatFormatting.GREEN));
        player.sendSystemMessage(Component.literal("  Each nutrient contribution is based on fullness; the sum is multiplied by diet balance.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        player.sendSystemMessage(footer());
        return 1;
    }

    private static int info(ServerPlayer player) {
        Optional<ResourceLocation> selected = PlayerProgress.selectedTree(player);
        player.sendSystemMessage(header("PLAYER PROGRESSION"));
        player.sendSystemMessage(field("Active tree", selected.map(Object::toString).orElse("None"),
                selected.isPresent() ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("  RPG STATS").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
        for (RpgStat stat : RpgStat.values()) {
            player.sendSystemMessage(field(title(stat.id()), Integer.toString(PlayerProgress.getStat(player, stat)), ChatFormatting.GOLD));
        }
        selected.flatMap(SkillTreeDefinition::find).ifPresent(tree -> {
            SkillPointGeneration.Breakdown value = SkillPointGeneration.calculate(player, tree);
            player.sendSystemMessage(Component.literal("  TRAINING").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
            player.sendSystemMessage(field("Total rate", String.format("%.3f points/day", value.totalPerDay()), ChatFormatting.GREEN));
            player.sendSystemMessage(field("Baseline", String.format("%.3f", value.baseline()), ChatFormatting.WHITE));
            player.sendSystemMessage(field("Nutrition", String.format("%.3f", value.nutritionBeforeBalance()), ChatFormatting.GREEN));
            player.sendSystemMessage(field("Balance", String.format("× %.3f", value.balanceMultiplier()), ChatFormatting.YELLOW));
            player.sendSystemMessage(field("RPG stats", String.format("× %.3f", value.statMultiplier()), ChatFormatting.GOLD));
            player.sendSystemMessage(field("Banked", String.format("%.3f", PlayerProgress.accumulatedPoints(player, tree.id())), ChatFormatting.AQUA));
        });
        player.sendSystemMessage(footer());
        return 1;
    }

    private static int setStat(Iterable<ServerPlayer> players, String statName, int value) {
        Optional<RpgStat> stat = RpgStat.parse(statName);
        if (stat.isEmpty()) return 0;
        int changed = 0;
        for (ServerPlayer player : players) {
            PlayerProgress.setStat(player, stat.get(), value);
            player.sendSystemMessage(success(title(stat.get().id()) + " set to " + PlayerProgress.getStat(player, stat.get()) + "."));
            changed++;
        }
        return changed;
    }

    private static Component header(String title) {
        return Component.literal("━━━ ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal("TERRASKILLS · " + title).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" ━━━").withStyle(ChatFormatting.DARK_AQUA));
    }

    private static Component footer() {
        return Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.DARK_AQUA);
    }

    private static Component field(String label, String value, ChatFormatting color) {
        return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(color));
    }

    private static Component nutrientLine(String name, SkillPointGeneration.NutrientValue nutrient) {
        int filled = (int) Math.round(nutrient.fullness() * 10);
        return Component.literal(String.format("  %-11s ", name + ":")).withStyle(ChatFormatting.GRAY)
                .append(Component.literal("▰".repeat(filled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("▱".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.format(" %5.1f%%", nutrient.fullness() * 100)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(String.format("  +%.3f/day", nutrient.pointsPerDay())).withStyle(ChatFormatting.GREEN));
    }

    private static Component success(String message) {
        return Component.literal("✔ ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
    }

    private static Component warning(String message) {
        return Component.literal("⚠ ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(ChatFormatting.YELLOW));
    }

    private static Component error(String message) {
        return Component.literal("✖ ").withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private static String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }

    private static String title(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String formatProgress(ServerPlayer player, SkillTreeDefinition tree) {
        return String.format("%.1f%%", Math.min(1, PlayerProgress.accumulatedPoints(player, tree.id())) * 100);
    }

    private static String timeUntilNext(ServerPlayer player, SkillTreeDefinition tree, double pointsPerDay, boolean active) {
        if (active && !ProgressionRuntime.isGenerating()) return "generation paused";
        if (pointsPerDay <= 0) return "never at current rate";
        double banked = PlayerProgress.accumulatedPoints(player, tree.id());
        if (banked >= 1) return "ready (awaiting valid Pufferfish category)";
        double remainingDays = (1 - banked) / pointsPerDay;
        long seconds = Math.max(1, Math.round(remainingDays * TerraSkillsConfig.REAL_MINUTES_PER_DAY.get() * 60));
        return formatDuration(seconds) + (active ? "" : " if resumed");
    }

    private static String formatDuration(long totalSeconds) {
        long days = totalSeconds / 86_400;
        long hours = totalSeconds % 86_400 / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long seconds = totalSeconds % 60;
        if (days > 0) return String.format("%dd %dh %dm", days, hours, minutes);
        if (hours > 0) return String.format("%dh %dm", hours, minutes);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return seconds + "s";
    }
}
