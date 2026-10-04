package com.terraskills.client;

import com.terraskills.network.ResearchQueuePayload;

/** Last server-approved queue snapshot. */
public final class ResearchClientState {
    private static ResearchQueuePayload queue = new ResearchQueuePayload(java.util.List.of(), java.util.List.of(), java.util.List.of(), 0);

    private ResearchClientState() {}

    public static void accept(ResearchQueuePayload payload) { queue = payload; }
    public static ResearchQueuePayload queue() { return queue; }
}
