package com.terraskills.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.terraskills.TerraSkills;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = TerraSkills.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ResearchKeybinds {
    private static final KeyMapping OPEN_RESEARCH = new KeyMapping("key.terraskills.open_research",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.terraskills");

    private ResearchKeybinds() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_RESEARCH);
    }

    @EventBusSubscriber(modid = TerraSkills.MOD_ID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_RESEARCH.consumeClick()) {
                if (minecraft.player != null && minecraft.screen == null) minecraft.setScreen(new ResearchScreen());
            }
        }
    }
}
