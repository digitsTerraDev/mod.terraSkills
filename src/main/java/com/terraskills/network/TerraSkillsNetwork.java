package com.terraskills.network;

import com.terraskills.client.SkillPointHud;
import com.terraskills.client.ResearchClientState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class TerraSkillsNetwork {
    private TerraSkillsNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(SkillProgressPayload.TYPE, SkillProgressPayload.STREAM_CODEC,
                (payload, context) -> SkillPointHud.accept(payload));
        event.registrar("1").playToClient(ResearchQueuePayload.TYPE, ResearchQueuePayload.STREAM_CODEC,
                (payload, context) -> ResearchClientState.accept(payload));
    }
}
