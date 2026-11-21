package com.example.thirdpersonhands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import org.lwjgl.glfw.GLFW;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shows a faded, outlined copy of your player at the position you were at
 * roughly one ping ago, plus boxes showing where moving entities (projectiles)
 * will be one ping from now.
 *
 * Press K (rebindable in Controls) to open the settings screen. Settings are
 * saved in config/thirdperson_hands.properties, which is also re-read every
 * couple of seconds if you edit it by hand.
 */
public class ThirdPersonHandsClient implements ClientModInitializer {

    private record Sample(long timeMs, Vec3 pos, float bodyYaw, float headYaw, float pitch) {}
    private record Pt(long timeMs, Vec3 pos) {}

    private static final int GHOST_ID = -424242;

    private final Map<Integer, ArrayDeque<Pt>> tracks = new HashMap<>();
    private long lastDebug = 0;
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private GhostPlayer ghost;
    private KeyMapping openKey;

    // Settings (defaults; overwritten from the config file)
    private boolean enabled = true;
    private boolean showModel = true;
    private boolean showBox = false;
    private boolean ghostOutline = true;     // coloured outline so the ghost is easy to see
    private int ghostOutlineRgb = 0x40C0FF;  // outline colour, RGB
    private boolean showProjectiles = true;  // predicted projectile boxes
    private double projectileBoxSize = 0.5;  // size of each predicted box, in blocks
    private double minProjectileSpeed = 1.0; // ignore entities slower than this (blocks/second)
    private Set<String> projectileTypes = new HashSet<>(); // empty = all entity types
    private boolean straightOnly = true;     // skip things that speed up/slow down/bounce (coins, falling items)
    private Set<String> ignoredTypes = new HashSet<>(Set.of("item", "experience_orb")); // never boxed
    private boolean debugEntities = false;   // brief text above the hotbar listing moving entity types
    private boolean showEquipment = false;   // armor / held items are always fully opaque
    private double hideNearDistance = 1.0;   // first person: hide ghost within this many blocks (0 = never hide)
    private int extraDelayMs = 0;            // added on top of your ping
    private int fixedDelayMs = -1;           // if >= 0, ignore ping and use this
    private double sizeScale = 1.0;          // box size only (1.0 = normal player size)
    private int color = 0xFFFF3030;          // box colour, ARGB

    private long lastConfigRead = 0;
    private final Path configPath =
        FabricLoader.getInstance().getConfigDir().resolve("thirdperson_hands.properties");

    @Override
    public void onInitializeClient() {
        readConfig();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.thirdperson_hands.open_settings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("thirdperson_hands", "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new SettingsScreen(this));
            }
        });

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
            ghost.glow = ghostOutline;
            ghost.outlineRgb = ghostOutlineRgb;
            // In first person, hide the ghost while it is close enough that the
            // camera would end up inside its head.
            boolean firstPerson = mc.options.getCameraType().isFirstPerson();
            ghost.hidden = firstPerson && hideNearDistance > 0
                && s.pos().distanceTo(me.getPosition(partial)) < hideNearDistance;
        } else if (ghost != null) {
            mc.level.removeEntity(GHOST_ID, Entity.RemovalReason.DISCARDED);
            ghost = null;
        }

        // --- predicted projectile boxes ---
        if (enabled && showProjectiles) {
            updateProjectiles(mc, me, partial, now, Math.max(0, delay));
        } else {
            tracks.clear();
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

    /**
     * Tracks every nearby moving entity, works out its speed, and draws where it
     * will be one delay (about your ping) from now. That is roughly where the
     * server will have it when it learns where you are standing right now.
     * Boxes turn red when they would overlap your real hitbox.
     */
    private void updateProjectiles(Minecraft mc, Player me, float partial, long now, long delayMs) {
        Set<Integer> seen = new HashSet<>();
        Vec3 myPos = me.getPosition(partial);
        AABB myBox = me.getBoundingBox();
        Map<String, int[]> counts = debugEntities ? new TreeMap<>() : null;
        int drawn = 0;
        Vec3 myVel = myVelocity(now);

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == me || e == ghost || e instanceof Player) continue;
            // Your mount and anything riding with you is not a projectile.
            if (me.getVehicle() != null && e.getRootVehicle() == me.getRootVehicle()) continue;

            Vec3 pos = e.getPosition(partial);
            if (pos.distanceToSqr(myPos) > 40 * 40) continue;

            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (ignoredTypes.contains(type)) continue;

            int id = e.getId();
            seen.add(id);
            ArrayDeque<Pt> tr = tracks.computeIfAbsent(id, k -> new ArrayDeque<>());
            tr.addLast(new Pt(now, pos));
            while (tr.size() > 2 && tr.peekFirst().timeMs() < now - 700) tr.removeFirst();

            // Velocity from the newest sample that is at least 100 ms old.
            Pt old = null;
            for (Pt p : tr) {
                if (now - p.timeMs() >= 100) old = p; else break;
            }
            if (old == null) continue;

            double dt = (now - old.timeMs()) / 1000.0;
            Vec3 vel = pos.subtract(old.pos()).scale(1.0 / dt);
            double speed = vel.length();
            if (speed < minProjectileSpeed || speed > 120) continue;

            // Things that travel along with you (mount parts, attached displays) are not projectiles.
            if (pos.distanceTo(myPos) < 4.0 && vel.subtract(myVel).length() < 1.5) continue;

            // Real projectiles keep a steady velocity. Falling or bouncing things
            // (like spilled coins) change speed quickly, so they are skipped.
            if (straightOnly) {
                Pt older = null;
                for (Pt p : tr) {
                    if (p.timeMs() <= old.timeMs() - 100) older = p; else break;
                }
                if (older == null) continue; // not enough history yet
                double dt2 = (old.timeMs() - older.timeMs()) / 1000.0;
                Vec3 vel2 = old.pos().subtract(older.pos()).scale(1.0 / dt2);
                if (vel.subtract(vel2).length() > Math.max(1.0, 0.2 * speed)) continue;
            }

            if (counts != null) counts.computeIfAbsent(type, k -> new int[1])[0]++;
            if (!projectileTypes.isEmpty() && !projectileTypes.contains(type)) continue;
            if (drawn >= 300) continue;

            Vec3 pred = pos.add(vel.scale(delayMs / 1000.0));
            double h = projectileBoxSize / 2.0;
            AABB pb = new AABB(pred.x - h, pred.y - h, pred.z - h, pred.x + h, pred.y + h, pred.z + h);
            boolean danger = pb.intersects(myBox);
            Gizmos.cuboid(pb, GizmoStyle.stroke(danger ? 0xFFFF2020 : 0xFFFFC030));
            drawn++;
        }

        tracks.keySet().retainAll(seen);

        if (counts != null && now - lastDebug > 5000) {
            lastDebug = now;
            StringBuilder sb = new StringBuilder("[Ghost debug] moving entities near you: ");
            if (counts.isEmpty()) {
                sb.append("none");
            } else {
                counts.forEach((k, v) -> sb.append(k).append(" x").append(v[0]).append("  "));
            }
            // Shown briefly above the hotbar, never added to chat.
            mc.gui.setOverlayMessage(Component.literal(sb.toString().trim()), false);
        }
    }

    /** Your own velocity in blocks per second, from the recent position history. */
    private Vec3 myVelocity(long now) {
        Sample newest = history.peekLast();
        Sample old = null;
        for (Sample s : history) {
            if (now - s.timeMs() >= 100) old = s; else break;
        }
        if (newest == null || old == null) return Vec3.ZERO;
        double dt = (newest.timeMs() - old.timeMs()) / 1000.0;
        return dt <= 0 ? Vec3.ZERO : newest.pos().subtract(old.pos()).scale(1.0 / dt);
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
     * A client-only copy of the player. Vanilla draws invisible players that
     * you are allowed to see as faded/translucent, so we mark it invisible but
     * visible-to-you. It can't be clicked, has no nametag, and never touches
     * the server.
     */
    private static class GhostPlayer extends RemotePlayer {
        private double lastTickX, lastTickZ;
        private boolean hasLastTick = false;
        boolean hidden = false;
        boolean glow = true;
        int outlineRgb = 0x40C0FF;

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
        public boolean isCurrentlyGlowing() {
            return glow; // vanilla draws a coloured outline around glowing entities
        }

        @Override
        public int getTeamColor() {
            return outlineRgb; // sets the outline colour
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

    // ------------------------------------------------------------------
    // Settings screen
    // ------------------------------------------------------------------

    private static class SettingsScreen extends Screen {
        private final ThirdPersonHandsClient m;

        SettingsScreen(ThirdPersonHandsClient m) {
            super(Component.literal("Ghost settings"));
            this.m = m;
        }

        @Override
        protected void init() {
            int colW = 150;
            int gap = 10;
            int left = this.width / 2 - colW - gap / 2;
            int right = this.width / 2 + gap / 2;
            int y0 = 36;
            int row = 24;

            int titleW = this.font.width(this.title);
            addRenderableWidget(new StringWidget((this.width - titleW) / 2, 14, titleW, 9, this.title, this.font));

            // Left column: on/off switches
            toggle(left, y0 + row * 0, "Ghost", () -> m.enabled, v -> m.enabled = v);
            toggle(left, y0 + row * 1, "Faded model", () -> m.showModel, v -> m.showModel = v);
            toggle(left, y0 + row * 2, "Outline", () -> m.ghostOutline, v -> m.ghostOutline = v);
            toggle(left, y0 + row * 3, "Armor + items", () -> m.showEquipment, v -> m.showEquipment = v);
            toggle(left, y0 + row * 4, "Hitbox outline", () -> m.showBox, v -> m.showBox = v);
            toggle(left, y0 + row * 5, "Projectile boxes", () -> m.showProjectiles, v -> m.showProjectiles = v);
            toggle(left, y0 + row * 6, "Straight only", () -> m.straightOnly, v -> m.straightOnly = v);
            toggle(left, y0 + row * 7, "Entity debug", () -> m.debugEntities, v -> m.debugEntities = v);

            // Right column: numbers
            stepper(right, y0 + row * 0, "Extra delay", " ms", 5, -200, 400, 0,
                () -> m.extraDelayMs, v -> m.extraDelayMs = (int) Math.round(v));
            stepper(right, y0 + row * 1, "Proj. size", "", 0.1, 0.1, 3.0, 1,
                () -> m.projectileBoxSize, v -> m.projectileBoxSize = v);
            stepper(right, y0 + row * 2, "Min speed", "", 0.5, 0.0, 30.0, 1,
                () -> m.minProjectileSpeed, v -> m.minProjectileSpeed = v);
            stepper(right, y0 + row * 3, "Hide near", "", 0.25, 0.0, 5.0, 2,
                () -> m.hideNearDistance, v -> m.hideNearDistance = v);

            // Right column: text fields
            int ty = y0 + row * 4;
            addRenderableWidget(new StringWidget(right, ty, colW, 9, Component.literal("Outline colour (hex)"), this.font));
            EditBox colorBox = new EditBox(this.font, right, ty + 10, colW, 20, Component.literal("Outline colour"));
            colorBox.setMaxLength(6);
            colorBox.setValue(String.format("%06X", m.ghostOutlineRgb));
            colorBox.setResponder(s -> {
                try {
                    m.ghostOutlineRgb = Integer.parseInt(s.trim(), 16) & 0xFFFFFF;
                    m.saveConfig();
                } catch (NumberFormatException ignored) {
                    // wait for a valid value
                }
            });
            addRenderableWidget(colorBox);

            addRenderableWidget(new StringWidget(right, ty + 34, colW, 9,
                Component.literal("Projectile types (blank = all)"), this.font));
            EditBox typesBox = new EditBox(this.font, right, ty + 44, colW, 20, Component.literal("Projectile types"));
            typesBox.setMaxLength(200);
            typesBox.setValue(String.join(",", m.projectileTypes));
            typesBox.setResponder(s -> {
                Set<String> types = new HashSet<>();
                for (String t : s.split(",")) {
                    String v = t.trim().toLowerCase();
                    if (!v.isEmpty()) types.add(v);
                }
                m.projectileTypes = types;
                m.saveConfig();
            });
            addRenderableWidget(typesBox);

            addRenderableWidget(new StringWidget(right, ty + 68, colW, 9,
                Component.literal("Ignored types"), this.font));
            EditBox ignoredBox = new EditBox(this.font, right, ty + 78, colW, 20, Component.literal("Ignored types"));
            ignoredBox.setMaxLength(200);
            ignoredBox.setValue(String.join(",", m.ignoredTypes));
            ignoredBox.setResponder(s -> {
                Set<String> types = new HashSet<>();
                for (String t : s.split(",")) {
                    String v = t.trim().toLowerCase();
                    if (!v.isEmpty()) types.add(v);
                }
                m.ignoredTypes = types;
                m.saveConfig();
            });
            addRenderableWidget(ignoredBox);

            addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
        }

        private static Component toggleText(String label, boolean on) {
            return Component.literal(label + ": " + (on ? "ON" : "OFF"));
        }

        private void toggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set) {
            addRenderableWidget(Button.builder(toggleText(label, get.getAsBoolean()), btn -> {
                set.accept(!get.getAsBoolean());
                m.saveConfig();
                btn.setMessage(toggleText(label, get.getAsBoolean()));
            }).bounds(x, y, 150, 20).build());
        }

        private static Component numText(String label, String unit, double v, int decimals) {
            return Component.literal(label + ": " + String.format("%." + decimals + "f", v) + unit);
        }

        private void stepper(int x, int y, String label, String unit, double step, double min, double max,
                             int decimals, DoubleSupplier get, DoubleConsumer set) {
            Button display = Button.builder(numText(label, unit, get.getAsDouble(), decimals), b -> {})
                .bounds(x + 22, y, 106, 20).build();
            display.active = false;

            Button minus = Button.builder(Component.literal("-"), b -> {
                set.accept(Math.max(min, get.getAsDouble() - step));
                m.saveConfig();
                display.setMessage(numText(label, unit, get.getAsDouble(), decimals));
            }).bounds(x, y, 20, 20).build();

            Button plus = Button.builder(Component.literal("+"), b -> {
                set.accept(Math.min(max, get.getAsDouble() + step));
                m.saveConfig();
                display.setMessage(numText(label, unit, get.getAsDouble(), decimals));
            }).bounds(x + 130, y, 20, 20).build();

            addRenderableWidget(minus);
            addRenderableWidget(display);
            addRenderableWidget(plus);
        }
    }

    // ------------------------------------------------------------------
    // Config file
    // ------------------------------------------------------------------

    private void saveConfig() {
        try {
            Properties d = new Properties();
            d.setProperty("enabled", String.valueOf(enabled));
            d.setProperty("showModel", String.valueOf(showModel));
            d.setProperty("showBox", String.valueOf(showBox));
            d.setProperty("showEquipment", String.valueOf(showEquipment));
            d.setProperty("hideNearDistance", String.valueOf(hideNearDistance));
            d.setProperty("ghostOutline", String.valueOf(ghostOutline));
            d.setProperty("ghostOutlineColorHex", String.format("%06X", ghostOutlineRgb));
            d.setProperty("showProjectiles", String.valueOf(showProjectiles));
            d.setProperty("projectileBoxSize", String.valueOf(projectileBoxSize));
            d.setProperty("minProjectileSpeed", String.valueOf(minProjectileSpeed));
            d.setProperty("projectileTypes", String.join(",", projectileTypes));
            d.setProperty("straightOnly", String.valueOf(straightOnly));
            d.setProperty("ignoredTypes", String.join(",", ignoredTypes));
            d.setProperty("debugEntities", String.valueOf(debugEntities));
            d.setProperty("extraDelayMs", String.valueOf(extraDelayMs));
            d.setProperty("fixedDelayMs", String.valueOf(fixedDelayMs));
            d.setProperty("sizeScale", String.valueOf(sizeScale));
            d.setProperty("colorHex", String.format("%08X", color));
            try (OutputStream out = Files.newOutputStream(configPath)) {
                d.store(out, "Third Person Hands - ghost. fixedDelayMs=-1 means use your ping.");
            }
            // Don't immediately re-read the file we just wrote.
            lastConfigRead = System.currentTimeMillis();
        } catch (IOException ignored) {
            // settings still apply for this session even if saving fails
        }
    }

    private void readConfig() {
        try {
            if (!Files.exists(configPath)) {
                saveConfig();
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
            ghostOutline = Boolean.parseBoolean(p.getProperty("ghostOutline", "true").trim());
            ghostOutlineRgb = (int) (Long.parseLong(p.getProperty("ghostOutlineColorHex", "40C0FF").trim(), 16) & 0xFFFFFF);
            showProjectiles = Boolean.parseBoolean(p.getProperty("showProjectiles", "true").trim());
            projectileBoxSize = Double.parseDouble(p.getProperty("projectileBoxSize", "0.5").trim());
            minProjectileSpeed = Double.parseDouble(p.getProperty("minProjectileSpeed", "1.0").trim());
            debugEntities = Boolean.parseBoolean(p.getProperty("debugEntities", "false").trim());
            straightOnly = Boolean.parseBoolean(p.getProperty("straightOnly", "true").trim());
            Set<String> ignored = new HashSet<>();
            for (String t : p.getProperty("ignoredTypes", "item,experience_orb").split(",")) {
                String s = t.trim().toLowerCase();
                if (!s.isEmpty()) ignored.add(s);
            }
            ignoredTypes = ignored;
            Set<String> types = new HashSet<>();
            for (String t : p.getProperty("projectileTypes", "").split(",")) {
                String s = t.trim().toLowerCase();
                if (!s.isEmpty()) types.add(s);
            }
            projectileTypes = types;
            extraDelayMs = Integer.parseInt(p.getProperty("extraDelayMs", "0").trim());
            fixedDelayMs = Integer.parseInt(p.getProperty("fixedDelayMs", "-1").trim());
            sizeScale = Double.parseDouble(p.getProperty("sizeScale", "1.0").trim());
            color = (int) Long.parseLong(p.getProperty("colorHex", "FFFF3030").trim(), 16);
        } catch (IOException | NumberFormatException ignored) {
            // keep previous values if the file is unreadable or malformed
        }
    }

}

