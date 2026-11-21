package com.example.thirdpersonhands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Properties;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a faded copy of your player at the position you were at roughly one
 * ping ago (where the server compares projectiles against you).
 *
 * Settings live in config/thirdperson_hands.properties and are re-read every
 * couple of seconds, so you can edit them while the game is running.
 */
public class ThirdPersonHandsClient implements ClientModInitializer {

    private record Sample(long timeMs, Vec3 pos, float bodyYaw, float headYaw, float pitch) {}

    private static final int GHOST_ID = -424242;

    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private GhostPlayer ghost;

    // Settings (defaults; overwritten from the config file)
    private boolean enabled = true;
    private boolean showModel = true;
    private boolean showBox = false;
    private boolean showEquipment = false;   // armor / held items are always fully opaque
    private double hideNearDistance = 1.0;   // first person: hide ghost within this many blocks (0 = never hide)
    private int extraDelayMs = 0;     // added on top of your ping
    private int fixedDelayMs = -1;    // if >= 0, ignore ping and use this
    private double sizeScale = 1.0;   // box size only (1.0 = normal player size)
    private int color = 0xFFFF3030;   // box colour, ARGB

    private long lastConfigRead = 0;
    private final Path configPath =
        FabricLoader.getInstance().getConfigDir().resolve("thirdperson_hands.properties");

    @Override
    public void onInitializeClient() {
        readConfig();
        // Runs once per rendered frame, so everything is smooth.
        LevelRenderEvents.END_EXTRACTION.register(context -> onFrame(Minecraft.getInstance()));
    }

    private void onFrame(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastConfigRead > 2000) {
            readConfig();
            lastConfigRead = now;
        }

        if (mc.player == null || mc.level == null) {
            history.clear();
            ghost = null;
            return;
        }

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Player me = mc.player;
        history.addLast(new Sample(
            now,
            me.getPosition(partial),
            Mth.rotLerp(partial, me.yBodyRotO, me.yBodyRot),
            Mth.rotLerp(partial, me.yHeadRotO, me.yHeadRot),
            me.getViewXRot(partial)));
        while (history.size() > 2 && history.peekFirst().timeMs() < now - 2000) {
            history.removeFirst();
        }

        long delay = fixedDelayMs >= 0 ? fixedDelayMs : currentPing(mc) + extraDelayMs;
        Sample s = sampleAt(now - Math.max(0, delay));

        // --- faded player model ---
        if (enabled && showModel && s != null) {
            if (ghost == null || ghost.level() != mc.level) {
                ghost = new GhostPlayer(mc.level);
                ghost.setId(GHOST_ID);
                mc.level.addEntity(ghost);
            }
            ghost.place(s);
            ghost.copyLookFrom(me, showEquipment);
            // In first person, hide the ghost while it is close enough that the
            // camera would end up inside its head.
            boolean firstPerson = mc.options.getCameraType().isFirstPerson();
            ghost.hidden = firstPerson && hideNearDistance > 0
                && s.pos().distanceTo(me.getPosition(partial)) < hideNearDistance;
        } else if (ghost != null) {
            mc.level.removeEntity(GHOST_ID, Entity.RemovalReason.DISCARDED);
            ghost = null;
        }

        // --- optional box ---
        if (enabled && showBox && s != null) {
            Vec3 p = s.pos();
            double w = 0.6 * sizeScale;
            double h = 1.8 * sizeScale;
            AABB box = new AABB(p.x - w / 2, p.y, p.z - w / 2, p.x + w / 2, p.y + h, p.z + w / 2);
            Gizmos.cuboid(box, GizmoStyle.stroke(color));
        }
    }

    private int currentPing(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** Interpolated state of the player at the given wall-clock time. */
    private Sample sampleAt(long targetMs) {
        Sample prev = null;
        for (Sample s : history) {
            if (s.timeMs() >= targetMs) {
                if (prev == null) return s;
                long span = s.timeMs() - prev.timeMs();
                float t = span <= 0 ? 1.0f : (float) (targetMs - prev.timeMs()) / span;
                return new Sample(
                    targetMs,
                    prev.pos().lerp(s.pos(), t),
                    Mth.rotLerp(t, prev.bodyYaw(), s.bodyYaw()),
                    Mth.rotLerp(t, prev.headYaw(), s.headYaw()),
                    Mth.lerp(t, prev.pitch(), s.pitch()));
            }
            prev = s;
        }
        return prev;
    }

    /**
     * A client-only copy of the player. Vanilla draws invisible entities that
     * you are allowed to see as faded/translucent, so we mark it invisible but
     * visible-to-you. It can't be clicked, has no nametag, and never touches
     * the server.
     */
    private static class GhostPlayer extends RemotePlayer {
        private double lastTickX, lastTickZ;
        private boolean hasLastTick = false;
        boolean hidden = false;

        GhostPlayer(ClientLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "ghost"));
            this.noPhysics = true;
            this.setInvisible(true);
        }

        void place(Sample s) {
            // Setting old == new position turns off vanilla's 20 Hz smoothing,
            // so the ghost follows the per-frame positions exactly.
            this.setPos(s.pos().x, s.pos().y, s.pos().z);
            this.xo = this.xOld = s.pos().x;
            this.yo = this.yOld = s.pos().y;
            this.zo = this.zOld = s.pos().z;
            this.yBodyRot = this.yBodyRotO = s.bodyYaw();
            this.yHeadRot = this.yHeadRotO = s.headYaw();
            this.setYRot(s.bodyYaw());
            this.yRotO = s.bodyYaw();
            this.setXRot(s.pitch());
            this.xRotO = s.pitch();
        }

        void copyLookFrom(Player me, boolean copyEquipment) {
            this.setPose(me.getPose());
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                // Armor and held items can't be faded by vanilla, so they are
                // left off by default to keep the ghost clearly see-through.
                this.setItemSlot(slot, copyEquipment ? me.getItemBySlot(slot).copy() : ItemStack.EMPTY);
            }
        }

        @Override
        public boolean shouldRender(double camX, double camY, double camZ) {
            return !hidden && super.shouldRender(camX, camY, camZ);
        }

        @Override
        public void tick() {
            // Drive the walking animation from how far the ghost moved this tick.
            if (hasLastTick) {
                this.updateWalkAnimation((float) Math.hypot(getX() - lastTickX, getZ() - lastTickZ));
            }
            lastTickX = getX();
            lastTickZ = getZ();
            hasLastTick = true;
        }

        @Override
        public boolean isInvisibleTo(Player player) {
            return false; // lets vanilla draw it faded instead of hiding it
        }

        @Override
        public boolean isPickable() {
            return false; // clicks and attacks pass straight through
        }

        @Override
        public boolean shouldShowName() {
            return false;
        }

        @Override
        public PlayerSkin getSkin() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null ? mc.player.getSkin() : super.getSkin();
        }
    }

    private void readConfig() {
        try {
            if (!Files.exists(configPath)) {
                Properties d = new Properties();
                d.setProperty("enabled", "true");
                d.setProperty("showModel", "true");
                d.setProperty("showBox", "false");
                d.setProperty("showEquipment", "false");
                d.setProperty("hideNearDistance", "1.0");
                d.setProperty("extraDelayMs", "0");
                d.setProperty("fixedDelayMs", "-1");
                d.setProperty("sizeScale", "1.0");
                d.setProperty("colorHex", "FFFF3030");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    d.store(out, "Third Person Hands - ghost. fixedDelayMs=-1 means use your ping.");
                }
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            showModel = Boolean.parseBoolean(p.getProperty("showModel", "true").trim());
            showBox = Boolean.parseBoolean(p.getProperty("showBox", "false").trim());
            showEquipment = Boolean.parseBoolean(p.getProperty("showEquipment", "false").trim());
            hideNearDistance = Double.parseDouble(p.getProperty("hideNearDistance", "1.0").trim());
            extraDelayMs = Integer.parseInt(p.getProperty("extraDelayMs", "0").trim());
            fixedDelayMs = Integer.parseInt(p.getProperty("fixedDelayMs", "-1").trim());
            sizeScale = Double.parseDouble(p.getProperty("sizeScale", "1.0").trim());
            color = (int) Long.parseLong(p.getProperty("colorHex", "FFFF3030").trim(), 16);
        } catch (IOException | NumberFormatException ignored) {
            // keep previous values if the file is unreadable or malformed
        }
    }
}
