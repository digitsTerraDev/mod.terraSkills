package com.terraskills.client;

import com.digitscodecompendium.terralib.client.gui.TerraButton;
import com.digitscodecompendium.terralib.client.gui.TerraGui;
import com.digitscodecompendium.terralib.client.gui.TerraUiTheme;
import com.terraskills.network.ResearchQueuePayload;
import com.terraskills.research.ResearchCatalog;
import com.terraskills.research.ResearchDefinition;
import com.terraskills.research.ResearchTrack;
import com.terraskills.research.ResearchTree;
import com.terraskills.progression.RpgStat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;

/** TerraLib-styled research terminal. Tree tabs select a catalogue; cards represent trainable skills. */
public final class ResearchScreen extends Screen {
    private static final TerraUiTheme THEME = TerraUiTheme.MACHINE;
    private static final int QUEUED_PIP = 0xFF3D9EDB;
    // Must fit a 854x480 window even when Minecraft's GUI scale halves its logical size.
    private static final int WIDTH = 400, HEIGHT = 220, QUEUE_WIDTH = 140, TREE_COLUMNS = 4, SKILL_COLUMNS = 2;
    private ResourceLocation selectedTree;
    private ResearchTrack hoveredTrack;
    private long hoveredSince;
    private int hoverMouseX, hoverMouseY;
    private int queueScroll;

    public ResearchScreen() { super(Component.translatable("terraskills.research.title")); }

    @Override protected void init() {
        if (selectedTree == null && !ResearchCatalog.trees().isEmpty()) selectedTree = ResearchCatalog.trees().getFirst().category();
        rebuildResearchWidgets();
    }

    private void rebuildResearchWidgets() {
        clearWidgets();
        int treeX = left() + 14, treeY = top() + 34;
        List<ResearchTree> trees = ResearchCatalog.trees();
        int treeWidth = (contentWidth() - (TREE_COLUMNS - 1) * 3) / TREE_COLUMNS;
        for (int index = 0; index < trees.size() && index < 12; index++) {
            ResearchTree tree = trees.get(index);
            int column = index % TREE_COLUMNS, row = index / TREE_COLUMNS;
            TerraButton button = TerraButton.toggle(Component.literal(font.plainSubstrByWidth(tree.title(), treeWidth - 8)), tree.category().equals(selectedTree), (ignored, selected) -> {
                // Tree tabs behave as a radio group: clicking the active tab keeps it active.
                selectedTree = tree.category();
                rebuildResearchWidgets();
            }).bounds(treeX + column * (treeWidth + 3), treeY + row * 16, treeWidth, 14).theme(THEME).build();
            addRenderableWidget(button);
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = left(), y = top(), queueX = x + WIDTH - QUEUE_WIDTH - 10;
        TerraGui.machinePanel(graphics, x, y, WIDTH, HEIGHT, THEME);
        graphics.drawCenteredString(font, title, x + WIDTH / 2, y + 13, THEME.text());

        TerraGui.raisedPanel(graphics, x + 8, y + 28, contentWidth() + 12, 57, THEME);

        TerraGui.recessedPanel(graphics, x + 8, skillsTop(), contentWidth() + 12, HEIGHT - (skillsTop() - y) - 9, THEME);
        graphics.drawString(font, Component.translatable("terraskills.research.skills"), x + 14, skillsTop() + 5, THEME.mutedText());
        ResearchTrack hovered = drawSkillCards(graphics, mouseX, mouseY);

        TerraGui.raisedPanel(graphics, queueX, y + 31, QUEUE_WIDTH + 2, HEIGHT - 40, THEME);
        drawQueue(graphics, queueX, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
        // Render after child widgets so the delayed detail panel is genuinely on top.
        updateHover(hovered, mouseX, mouseY);
        if (hoveredTrack != null && System.currentTimeMillis() - hoveredSince >= 1_000) drawDetailPanel(graphics, hoveredTrack);
    }

    private ResearchTrack drawSkillCards(GuiGraphics graphics, int mouseX, int mouseY) {
        List<ResearchTrack> tracks = visibleTracks();
        if (tracks.isEmpty()) {
            graphics.drawString(font, Component.translatable("terraskills.research.none"), left() + 16, skillsTop() + 53, THEME.mutedText());
            return null;
        }
        ResearchTrack result = null;
        int cardWidth = (contentWidth() - 4) / SKILL_COLUMNS;
        for (int index = 0; index < tracks.size() && index < 12; index++) {
            ResearchTrack track = tracks.get(index); int column = index % SKILL_COLUMNS, row = index / SKILL_COLUMNS;
            int x = left() + 14 + column * (cardWidth + 4), y = skillsTop() + 20 + row * 14;
            boolean overAdd = inside(mouseX, mouseY, x + cardWidth - 12, y, 12, 12);
            boolean hover = inside(mouseX, mouseY, x, y, cardWidth, 12) && !overAdd;
            if (hover) result = track;
            if (hover) TerraGui.raisedPanel(graphics, x, y, cardWidth, 12, THEME);
            else TerraGui.recessedPanel(graphics, x, y, cardWidth, 12, THEME);
            drawFittedString(graphics, track.title(), x + 4, y + 2, 30, THEME.text());
            drawLevelPips(graphics, track, x + 36, y + 2);
            int next = nextLevel(track);
            boolean complete = next >= track.levels().size();
            boolean researchable = !complete && canResearch(track, next);
            String action = complete ? "Done" : time(estimatedDuration(track, next));
            int actionColor = complete ? THEME.positive() : researchable ? THEME.positive() : THEME.mutedText();
            drawFittedString(graphics, action, x + cardWidth - 35, y + 2, 25, actionColor);
            if (!complete) graphics.drawString(font, researchable ? "+" : "-", x + cardWidth - 8, y + 2, researchable ? THEME.text() : THEME.mutedText());
        }
        return result;
    }

    private void drawLevelPips(GuiGraphics graphics, ResearchTrack track, int x, int y) {
        int completed = completedLevels(track);
        for (int level = 0; level < track.levels().size(); level++) {
            ResearchDefinition definition = track.levels().get(level);
            int color = level < completed ? THEME.positive() : queued(definition) ? QUEUED_PIP : THEME.frameDark();
            TerraGui.colorPip(graphics, x + level * 7, y, color, THEME);
        }
    }

    private void drawQueue(GuiGraphics graphics, int x, int mouseX, int mouseY) {
        ResearchQueuePayload queue = ResearchClientState.queue();
        graphics.drawString(font, Component.translatable("terraskills.research.queue"), x + 9, top() + 40, THEME.text());
        String completionTime = time(queue.totalRemainingMillis());
        graphics.drawString(font, completionTime, x + QUEUE_WIDTH - 9 - font.width(completionTime), top() + 40, THEME.mutedText());
        if (queue.entries().isEmpty()) {
            graphics.drawString(font, Component.translatable("terraskills.research.queue_empty"), x + 9, top() + 63, THEME.mutedText());
            return;
        }
        int visibleRows = Math.max(1, (HEIGHT - 63) / 23);
        queueScroll = Math.clamp(queueScroll, 0, Math.max(0, queue.entries().size() - visibleRows));
        for (int index = 0; index < visibleRows && queueScroll + index < queue.entries().size(); index++) {
            int entryIndex = queueScroll + index;
            ResearchQueuePayload.Entry entry = queue.entries().get(entryIndex); int rowY = top() + 58 + index * 23;
            boolean hovered = inside(mouseX, mouseY, x + 7, rowY, QUEUE_WIDTH - 12, 21);
            TerraGui.recessedPanel(graphics, x + 7, rowY, QUEUE_WIDTH - 12, 21, THEME);
            graphics.drawString(font, title(entry.skill()), x + 14, rowY + 4, THEME.text());
            double progress = entry.requiredPoints() == 0 ? 0 : entry.points() / entry.requiredPoints();
            // The seventh argument is the number of visual segments, not an ARGB color.
            // TerraLib supplies the colour from the selected theme.
            String remaining = time(entry.remainingMillis());
            graphics.drawString(font, remaining, x + QUEUE_WIDTH - 14 - font.width(remaining), rowY + 4, THEME.mutedText());
            TerraGui.progressBar(graphics, x + 14, rowY + 15, QUEUE_WIDTH - 28, 4, progress, 0, THEME);
            if (hovered) drawQueueActions(graphics, x, rowY, entryIndex, queue.entries().size());
        }
        if (queue.entries().size() > visibleRows) graphics.drawString(font, (queueScroll + 1) + "-" + Math.min(queueScroll + visibleRows, queue.entries().size()) + "/" + queue.entries().size(), x + 9, top() + HEIGHT - 13, THEME.mutedText());
    }

    /** Row actions only occupy visual space while the player is working with that row. */
    private void drawQueueActions(GuiGraphics graphics, int queueX, int rowY, int index, int queueSize) {
        int actionX = queueX + QUEUE_WIDTH - 65;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(actionX - 1, rowY + 2, actionX + 58, rowY + 18, 0xFF080A0B);
        TerraGui.raisedPanel(graphics, actionX, rowY + 3, 57, 14, THEME);
        graphics.drawCenteredString(font, "^", actionX + 10, rowY + 5, index > 0 ? THEME.text() : THEME.mutedText());
        graphics.drawCenteredString(font, "v", actionX + 28, rowY + 5, index < queueSize - 1 ? THEME.text() : THEME.mutedText());
        graphics.drawCenteredString(font, "x", actionX + 46, rowY + 5, canCancel(index) ? THEME.negative() : THEME.mutedText());
        graphics.pose().popPose();
    }

    private void drawDetailPanel(GuiGraphics graphics, ResearchTrack track) {
        int x = left() + 20, y = skillsTop() + 4, width = contentWidth() - 12;
        int next = nextLevel(track);
        List<String> requirements = next < track.levels().size() ? requirementsFor(track, next) : List.of();
        List<FormattedCharSequence> description = font.split(Component.literal(track.description()), width - 20);
        List<FormattedCharSequence> requirementLines = requirements.isEmpty() ? List.of() : font.split(Component.literal("Requires: " + String.join(", ", requirements)), width - 20);
        List<FormattedCharSequence> effectLines = track.researchBonuses().isEmpty() ? List.of() : font.split(Component.literal("Effect: " + track.researchBonuses().getFirst()), width - 20);
        int height = 55 + description.size() * 10 + requirementLines.size() * 10 + effectLines.size() * 10 + 8;
        // Widgets such as the search field render on their own layer. Give the delayed card
        // an opaque backing and a higher depth so it always reads as a real tooltip.
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0xFF080A0B);
        TerraGui.raisedPanel(graphics, x, y, width, height, THEME);
        graphics.drawString(font, Component.literal(track.title()).withStyle(ChatFormatting.BOLD), x + 10, y + 10, THEME.text());
        int textY = y + 28;
        for (FormattedCharSequence line : description) { graphics.drawString(font, line, x + 10, textY, THEME.mutedText()); textY += 10; }
        for (FormattedCharSequence line : requirementLines) { graphics.drawString(font, line, x + 10, textY + 2, THEME.progressBright()); textY += 10; }
        drawRpgAffinities(graphics, track, x + 10, textY + 3);
        textY += 13;
        String cost = next < track.levels().size() ? track.levels().get(next).requiredPoints() + " research points" : "Complete";
        graphics.drawString(font, "Next: " + (next < track.levels().size() ? roman(next + 1) + "  |  " : "") + cost, x + 10, textY + 3, THEME.positive());
        textY += 13;
        for (FormattedCharSequence line : effectLines) { graphics.drawString(font, line, x + 10, textY + 3, THEME.mutedText()); textY += 10; }
        graphics.pose().popPose();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        int cardWidth = (contentWidth() - 4) / SKILL_COLUMNS;
        for (int index = 0; index < visibleTracks().size() && index < 12; index++) {
            ResearchTrack track = visibleTracks().get(index); int column = index % SKILL_COLUMNS, row = index / SKILL_COLUMNS;
            int x = left() + 14 + column * (cardWidth + 4), y = skillsTop() + 20 + row * 14;
            if (inside(mouseX, mouseY, x + cardWidth - 12, y, 12, 12)) {
                int next = nextLevel(track);
                if (next < track.levels().size() && canResearch(track, next)) command("ts research start \"" + track.levels().get(next).id() + "\"");
                return true;
            }
        }
        int queueX = left() + WIDTH - QUEUE_WIDTH - 10;
        int visibleRows = Math.max(1, (HEIGHT - 63) / 23);
        for (int index = 0; index < visibleRows && queueScroll + index < ResearchClientState.queue().entries().size(); index++) {
            int entryIndex = queueScroll + index, rowY = top() + 58 + index * 23, actionX = queueX + QUEUE_WIDTH - 65;
            if (entryIndex > 0 && inside(mouseX, mouseY, actionX, rowY + 3, 18, 14)) command("ts research move " + (entryIndex + 1) + " " + entryIndex);
            if (entryIndex < ResearchClientState.queue().entries().size() - 1 && inside(mouseX, mouseY, actionX + 19, rowY + 3, 18, 14)) command("ts research move " + (entryIndex + 1) + " " + (entryIndex + 2));
            if (canCancel(entryIndex) && inside(mouseX, mouseY, actionX + 38, rowY + 3, 18, 14)) command("ts research cancel " + (entryIndex + 1));
        }
        return true;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int queueX = left() + WIDTH - QUEUE_WIDTH - 10;
        if (!inside(mouseX, mouseY, queueX, top() + 31, QUEUE_WIDTH + 2, HEIGHT - 40)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int visibleRows = Math.max(1, (HEIGHT - 63) / 23);
        int maximum = Math.max(0, ResearchClientState.queue().entries().size() - visibleRows);
        queueScroll = Math.clamp(queueScroll - (int) Math.signum(scrollY), 0, maximum);
        return true;
    }

    private List<ResearchTrack> visibleTracks() {
        return ResearchCatalog.tracks().stream().filter(track -> track.category().equals(selectedTree)).toList();
    }
    private void updateHover(ResearchTrack track, int mouseX, int mouseY) {
        if (track != hoveredTrack || mouseX != hoverMouseX || mouseY != hoverMouseY) {
            hoveredTrack = track;
            hoveredSince = System.currentTimeMillis();
            hoverMouseX = mouseX;
            hoverMouseY = mouseY;
        }
    }
    private boolean queued(ResearchDefinition definition) { return ResearchClientState.queue().entries().stream().anyMatch(entry -> entry.category().equals(definition.category().toString()) && entry.skill().equals(definition.skill())); }
    private String trackKey(ResearchTrack track) { return track.category() + "/" + track.id(); }
    private int completedLevels(ResearchTrack track) { return ResearchClientState.queue().tracks().stream().filter(progress -> progress.category().equals(trackKey(track))).mapToInt(ResearchQueuePayload.TrackProgress::completedLevels).findFirst().orElse(0); }
    private long estimatedDuration(ResearchTrack track, int level) {
        long millisPerPoint = ResearchClientState.queue().tracks().stream().filter(progress -> progress.category().equals(trackKey(track)))
                .mapToLong(ResearchQueuePayload.TrackProgress::millisPerPoint).findFirst().orElse(Long.MAX_VALUE);
        int points = track.levels().get(level).requiredPoints();
        return millisPerPoint == Long.MAX_VALUE || points > Long.MAX_VALUE / millisPerPoint ? Long.MAX_VALUE : points * millisPerPoint;
    }
    private int nextLevel(ResearchTrack track) { return Math.min(track.levels().size(), completedLevels(track) + (int) ResearchClientState.queue().entries().stream().filter(entry -> track.levels().stream().anyMatch(level -> level.category().toString().equals(entry.category()) && level.skill().equals(entry.skill()))).count()); }
    private boolean canResearch(ResearchTrack track, int level) {
        ResearchDefinition definition = track.levels().get(level);
        if (ResearchClientState.queue().availableResearch().contains(definition.id())) return true;
        if (level > 0) return track.levels().subList(0, level).stream().allMatch(this::locallySatisfied);
        return track.prerequisites().stream().map(skill -> ResearchCatalog.find(track.category(), skill).orElse(null)).allMatch(requirement -> requirement != null && locallySatisfied(requirement));
    }
    private boolean locallySatisfied(ResearchDefinition definition) {
        if (queued(definition)) return true;
        return ResearchCatalog.track(definition.category(), definition.skill()).map(track -> completedLevels(track) > track.levels().indexOf(definition)).orElse(false);
    }
    private List<String> requirementsFor(ResearchTrack track, int level) {
        if (level > 0) return List.of(requirementName(track.levels().get(level - 1)));
        return track.prerequisites().stream().map(skill -> ResearchCatalog.find(track.category(), skill).map(this::requirementName).orElse(skill)).toList();
    }
    private String requirementName(ResearchDefinition definition) {
        return ResearchCatalog.track(definition.category(), definition.skill()).map(track -> track.title() + " " + roman(track.levels().indexOf(definition) + 1)).orElse(definition.skill());
    }
    private static String roman(int value) { return switch (value) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> Integer.toString(value); }; }
    private void drawRpgAffinities(GuiGraphics graphics, ResearchTrack track, int x, int y) {
        RpgStat primary = track.levels().getFirst().primaryStat(), secondary = track.levels().getFirst().secondaryStat();
        graphics.drawString(font, "Skills: ", x, y, THEME.mutedText());
        int cursor = x + font.width("Skills: ");
        cursor = drawStatMarker(graphics, primary, cursor, y);
        graphics.drawString(font, " / ", cursor, y, THEME.mutedText());
        drawStatMarker(graphics, secondary, cursor + font.width(" / "), y);
    }
    private int drawStatMarker(GuiGraphics graphics, RpgStat stat, int x, int y) {
        String label = switch (stat) { case MIGHT -> "M"; case FINESSE -> "F"; case ENDURANCE -> "E"; case INTELLIGENCE -> "I"; case INSTINCT -> "N"; };
        int color = switch (stat) { case MIGHT -> 0xFFE76A6A; case FINESSE -> 0xFF65D98E; case ENDURANCE -> 0xFFE5BE55; case INTELLIGENCE -> 0xFF66A9FF; case INSTINCT -> 0xFFC788EE; };
        graphics.drawString(font, label, x, y, color);
        return x + font.width(label);
    }
    /** A prerequisite level cannot be removed while a later level of the same skill is queued. */
    private boolean canCancel(int queueIndex) {
        List<ResearchQueuePayload.Entry> entries = ResearchClientState.queue().entries();
        if (queueIndex < 0 || queueIndex >= entries.size()) return false;
        ResearchQueuePayload.Entry target = entries.get(queueIndex);
        return ResearchCatalog.track(net.minecraft.resources.ResourceLocation.parse(target.category()), target.skill()).map(track -> {
            int targetLevel = levelInTrack(track, target.skill());
            return entries.stream().noneMatch(entry -> entry.category().equals(target.category()) && levelInTrack(track, entry.skill()) > targetLevel);
        }).orElse(true);
    }
    private static int levelInTrack(ResearchTrack track, String skill) { return (int) track.levels().stream().map(ResearchDefinition::skill).toList().indexOf(skill); }
    private void command(String value) { if (minecraft != null && minecraft.player != null) minecraft.player.connection.sendCommand(value); }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
    private int left() { return (width - WIDTH) / 2; }
    private int top() { return (height - HEIGHT) / 2; }
    private int contentWidth() { return WIDTH - QUEUE_WIDTH - 28; }
    private int skillsTop() { return top() + 90; }
    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) { return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height; }
    /** Scales compact-card text just enough to fit its allotted width instead of cutting it off. */
    private void drawFittedString(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        float scale = Math.min(0.8F, maxWidth / (float) Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, Math.round(x / scale), Math.round(y / scale), color);
        graphics.pose().popPose();
    }
    private static String title(String id) { String[] words = id.replace('_', ' ').split(" "); StringBuilder result = new StringBuilder(); for (String word : words) { if (!result.isEmpty()) result.append(' '); result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); } return result.toString(); }
    private static String time(long millis) { if (millis == Long.MAX_VALUE) return "paused"; long seconds = Math.max(0, millis / 1000), hours = seconds / 3600, minutes = seconds % 3600 / 60; return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m"; }
}
