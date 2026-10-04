package com.terraskills.network;

import com.terraskills.TerraSkills;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Client snapshot for the EVE-style queue pane. */
public record ResearchQueuePayload(List<Entry> entries, List<TrackProgress> tracks, List<String> availableResearch, long totalRemainingMillis) implements CustomPacketPayload {
    public record Entry(String category, String skill, double points, int requiredPoints, long remainingMillis) {}
    /** Server-calculated time for one research point in this skill, avoiding client config guesses. */
    public record TrackProgress(String category, int completedLevels, long millisPerPoint) {}

    public static final Type<ResearchQueuePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TerraSkills.MOD_ID, "research_queue"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchQueuePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.entries.size());
                for (Entry entry : payload.entries) {
                    buffer.writeUtf(entry.category); buffer.writeUtf(entry.skill); buffer.writeDouble(entry.points);
                    buffer.writeVarInt(entry.requiredPoints); buffer.writeLong(entry.remainingMillis);
                }
                buffer.writeVarInt(payload.tracks.size());
                for (TrackProgress track : payload.tracks) {
                    buffer.writeUtf(track.category); buffer.writeVarInt(track.completedLevels); buffer.writeLong(track.millisPerPoint);
                }
                buffer.writeVarInt(payload.availableResearch.size());
                for (String id : payload.availableResearch) buffer.writeUtf(id);
                buffer.writeLong(payload.totalRemainingMillis);
            },
            buffer -> {
                int size = buffer.readVarInt();
                List<Entry> entries = new ArrayList<>(size);
                for (int index = 0; index < size; index++) entries.add(new Entry(buffer.readUtf(), buffer.readUtf(), buffer.readDouble(),
                        buffer.readVarInt(), buffer.readLong()));
                int trackCount = buffer.readVarInt();
                List<TrackProgress> tracks = new ArrayList<>(trackCount);
                for (int index = 0; index < trackCount; index++) tracks.add(new TrackProgress(buffer.readUtf(), buffer.readVarInt(), buffer.readLong()));
                int availableCount = buffer.readVarInt();
                List<String> available = new ArrayList<>(availableCount);
                for (int index = 0; index < availableCount; index++) available.add(buffer.readUtf());
                return new ResearchQueuePayload(entries, tracks, available, buffer.readLong());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
