package com.example.thirdpersonhands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Properties;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Stage 1: draws a "ghost" hitbox at the position you were at roughly one
 * ping ago, which is where the server compares projectiles against you.
 *
 * Settings live in config/thirdperson_hands.properties and are re-read every
 * couple of seconds, so you can edit them while the game is running.
 */
public class ThirdPersonHandsClient implements ClientModInitializer {

    private record Sample(long timeMs, Vec3 pos) {}

    private final ArrayDeque<Sample> history = new ArrayDeque<>();

    // Settings (defaults; overwritten from the config file)
    private boolean enabled = true;
    private int extraDelayMs = 0;     // added on top of your ping
    private int fixedDelayMs = -1;    // if >= 0, ignore ping and use this
    private double sizeScale = 1.0;   // 1.0 = normal player size
    private int color = 0xFFFF3030;   // ARGB

    private long lastConfigRead = 0;
    private final Path configPath =
        FabricLoader.getInstance().getConfigDir().resolve("thirdperson_hands.properties");

    @Override
    public void onInitializeClient() {
        readConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastConfigRead > 2000) {
            readConfig();
            lastConfigRead = now;
        }

        if (mc.player == null || mc.level == null) {
            history.clear();
            return;
        }

        history.addLast(new Sample(now, mc.player.position()));
        while (history.size() > 2 && history.peekFirst().timeMs() < now - 2000) {
            history.removeFirst();
        }

        if (!enabled) return;

        long delay = fixedDelayMs >= 0 ? fixedDelayMs : currentPing(mc) + extraDelayMs;
        Vec3 p = positionAt(now - Math.max(0, delay));
        if (p == null) return;

        double w = 0.6 * sizeScale;
        double h = 1.8 * sizeScale;
        AABB box = new AABB(p.x - w / 2, p.y, p.z - w / 2, p.x + w / 2, p.y + h, p.z + w / 2);
        Gizmos.cuboid(box, GizmoStyle.stroke(color));
    }

    private int currentPing(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** Interpolated position of the player at the given wall-clock time. */
    private Vec3 positionAt(long targetMs) {
        Sample prev = null;
        for (Sample s : history) {
            if (s.timeMs() >= targetMs) {
                if (prev == null) return s.pos();
                long span = s.timeMs() - prev.timeMs();
                double t = span <= 0 ? 1.0 : (double) (targetMs - prev.timeMs()) / span;
                return prev.pos().lerp(s.pos(), t);
            }
            prev = s;
        }
        return prev == null ? null : prev.pos();
    }

    private void readConfig() {
        try {
            if (!Files.exists(configPath)) {
                Properties d = new Properties();
                d.setProperty("enabled", "true");
                d.setProperty("extraDelayMs", "0");
                d.setProperty("fixedDelayMs", "-1");
                d.setProperty("sizeScale", "1.0");
                d.setProperty("colorHex", "FFFF3030");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    d.store(out, "Third Person Hands - ghost hitbox. fixedDelayMs=-1 means use your ping.");
                }
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            extraDelayMs = Integer.parseInt(p.getProperty("extraDelayMs", "0").trim());
            fixedDelayMs = Integer.parseInt(p.getProperty("fixedDelayMs", "-1").trim());
            sizeScale = Double.parseDouble(p.getProperty("sizeScale", "1.0").trim());
            color = (int) Long.parseLong(p.getProperty("colorHex", "FFFF3030").trim(), 16);
        } catch (IOException | NumberFormatException ignored) {
            // keep previous values if the file is unreadable or malformed
        }
    }
}
