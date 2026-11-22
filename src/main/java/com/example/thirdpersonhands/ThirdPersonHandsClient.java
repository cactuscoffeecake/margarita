package com.example.thirdpersonhands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Third Person Hands. Three independent features, one menu (press K):
 *
 *  1. Hands in third person: your first-person hands and held items stay on
 *     screen in F5 (done by GameRendererMixin).
 *  2. Ping ghost: a faded, outlined copy of your player at the spot you were at
 *     about one ping ago, which is where the server compares things against you.
 *  3. Mob model: shows your character as any vanilla mob (client-side only).
 *
 * All settings are saved in config/thirdperson_hands.properties.
 */
public class ThirdPersonHandsClient implements ClientModInitializer {

    private record Sample(long timeMs, Vec3 pos, float bodyYaw, float headYaw, float pitch) {}
    private record Entry(EntityType<?> type, String id, String name, String lower) {}

    private static final int GHOST_ID = -424242;
    private static final int DUMMY_ID = -515151;
    private static final int GHOST_MOB_ID = -626262;

    // ---- ghost state ----
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private GhostPlayer ghost;
    // outline-only ghost used when the ghost shape is "mob"
    private LivingEntity ghostMob;
    private EntityType<?> ghostMobType;
    private boolean ghostMobBaby;
    private double lastGhostX, lastGhostY, lastGhostZ;
    private boolean hasLastGhost = false;

    // ---- ghost settings ----
    private boolean ghostEnabled = true;
    private boolean showModel = true;        // faded player model
    private boolean ghostOutline = true;     // coloured outline so the ghost is easy to see
    private int ghostOutlineRgb = 0x40C0FF;  // outline colour, RGB
    private boolean showEquipment = false;   // armor / held items are always fully opaque
    private boolean showBox = false;         // plain box outline instead of / as well as the model
    private double hideNearDistance = 1.0;   // first person: hide ghost within this many blocks (0 = never hide)
    private int extraDelayMs = 0;            // added on top of your ping
    private int fixedDelayMs = -1;           // if >= 0, ignore ping and use this
    private boolean ghostAsMob = false;      // ghost looks like your chosen mob (outline only) instead of a player

    // ---- mob model state and settings ----
    private Entity dummy;
    private EntityType<?> dummyType;
    private boolean dummyBaby;
    private double appliedScale = 1.0;
    private List<Entry> entries;
    private double lastTickX, lastTickY, lastTickZ;
    private boolean hasLastTick = false;
    private boolean mobEnabled = false;      // off until you pick a mob
    private String mobId = "minecraft:zombie";
    private boolean baby = false;
    private boolean mobCopyGear = false;
    private double mobScale = 1.0;
    private double hoverHeight = 1.25;       // blocks that swimming / flying mobs float above the ground
    private AABB pendingBox;                 // hitbox gizmo, drawn at END_EXTRACTION

    // Mobs that should look like they are swimming / flying: they get lifted off the ground.
    private static final Set<String> AQUATIC = Set.of(
        "cod", "salmon", "pufferfish", "tropical_fish", "squid", "glow_squid", "dolphin",
        "guardian", "elder_guardian", "axolotl", "tadpole", "turtle", "nautilus", "zombie_nautilus");
    private static final Set<String> FLYING = Set.of(
        "bat", "bee", "parrot", "phantom", "allay", "vex", "blaze", "ghast", "happy_ghast",
        "ender_dragon", "wither");

    private long lastConfigRead = 0;
    private KeyMapping openKey;
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

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // Once per rendered frame. This MUST run at the START of extraction: the game copies every
        // entity's position into its render state during extraction, so moving the stand-in after
        // that makes it lag one frame behind the camera (that was the flicker).
        LevelRenderEvents.START_EXTRACTION.register(context -> onFrame(Minecraft.getInstance()));
        // Debug gizmos are drawn at the end instead.
        LevelRenderEvents.END_EXTRACTION.register(context -> drawPendingBox());
    }

    // ------------------------------------------------------------------
    // Per game tick (20 per second): key, mob walk animation, mob gear
    // ------------------------------------------------------------------

    private void onTick(Minecraft mc) {
        while (openKey.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new MainScreen(this));
        }

        Player me = mc.player;
        tickGhostMob(mc, me);
        if (me == null || mc.level == null || !(dummy instanceof LivingEntity d) || d.level() != mc.level) {
            hasLastTick = false;
            return;
        }

        // Drive the mob's walking animation from how far you moved this tick.
        if (hasLastTick) {
            d.xo = lastTickX;
            d.yo = lastTickY;
            d.zo = lastTickZ;
        } else {
            d.xo = me.getX();
            d.yo = me.getY();
            d.zo = me.getZ();
        }
        d.setPos(me.getX(), me.getY(), me.getZ());
        d.calculateEntityAnimation(false);
        lastTickX = me.getX();
        lastTickY = me.getY();
        lastTickZ = me.getZ();
        hasLastTick = true;

        keepSwimming(d);

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack want = mobCopyGear ? me.getItemBySlot(slot).copy() : ItemStack.EMPTY;
            if (!ItemStack.matches(d.getItemBySlot(slot), want)) {
                d.setItemSlot(slot, want);
            }
        }
    }

    // ------------------------------------------------------------------
    // Per rendered frame
    // ------------------------------------------------------------------

    private void onFrame(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastConfigRead > 2000) {
            readConfig();
            lastConfigRead = now;
        }

        ClientLevel level = mc.level;
        Player me = mc.player;
        if (level == null || me == null) {
            history.clear();
            ghost = null;
            ghostMob = null;
            ghostMobType = null;
            ModState.ghostMob = null;
            dummy = null;
            dummyType = null;
            ModState.dummy = null;
            ModState.hidePlayer = false;
            ModState.hideDummy = false;
            ModState.dragonScale = 1.0f;
            pendingBox = null;
            return;
        }

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        updateGhost(mc, level, me, partial, now);
        updateMob(mc, level, me, partial);

        // The ender dragon renderer ignores the scale attribute, so a mixin shrinks it using this.
        EntityType<?> t = mobEnabled ? resolveType(mobId) : null;
        ModState.dragonScale = t == EntityType.ENDER_DRAGON ? (float) (0.25 * mobScale) : 1.0f;
    }

    private void drawPendingBox() {
        if (pendingBox != null) Gizmos.cuboid(pendingBox, GizmoStyle.stroke(0xFFFF3030));
    }

    // ---------------- per-type helpers ----------------

    private static String pathOf(EntityType<?> t) {
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(t);
        return key == null ? "" : key.getPath();
    }

    private static boolean isAquatic(EntityType<?> t) {
        return AQUATIC.contains(pathOf(t));
    }

    private static boolean isFloaty(EntityType<?> t) {
        String p = pathOf(t);
        return AQUATIC.contains(p) || FLYING.contains(p);
    }

    /** Positions and rotates a stand-in mob (the model or the ghost) exactly, with no 20 Hz smoothing. */
    private void poseMob(LivingEntity d, EntityType<?> type, Vec3 p, float body, float head, float pitch) {
        double y = p.y + (isFloaty(type) ? hoverHeight : 0.0);
        // The ender dragon's model faces the opposite way to every other mob.
        float off = type == EntityType.ENDER_DRAGON ? 180.0f : 0.0f;
        float b = body + off;
        float h = head + off;

        d.setPos(p.x, y, p.z);
        d.xo = d.xOld = p.x;
        d.yo = d.yOld = y;
        d.zo = d.zOld = p.z;
        d.yBodyRot = d.yBodyRotO = b;
        d.yHeadRot = d.yHeadRotO = h;
        d.setYRot(b);
        d.yRotO = b;
        d.setXRot(pitch);
        d.xRotO = pitch;
        d.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * A squid's tentacles only animate while a counter runs, and on the client the counter freezes
     * until the server sends event 19. There is no server here, so restart it ourselves.
     */
    private static void keepSwimming(LivingEntity e) {
        try {
            java.lang.reflect.Field f = e.getClass().getField("tentacleMovement");
            if (f.getFloat(e) >= 3.14f) e.handleEntityEvent((byte) 19);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not a squid
        }
    }

    // ---------------- ping ghost ----------------

    private void updateGhost(Minecraft mc, ClientLevel level, Player me, float partial, long now) {
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

        // Ghost shape: player model (faded) or your chosen mob (outline only).
        boolean wantGhost = ghostEnabled && showModel && s != null;
        boolean useMobGhost = wantGhost && ghostAsMob && mobEnabled && resolveType(mobId) != null;
        boolean usePlayerGhost = wantGhost && !useMobGhost;
        if (useMobGhost) {
            updateMobGhost(mc, level, me, s, partial);
        } else {
            removeMobGhost(level);
        }

        if (usePlayerGhost) {
            if (ghost == null || ghost.level() != level) {
                ghost = new GhostPlayer(level);
                ghost.setId(GHOST_ID);
                level.addEntity(ghost);
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
            level.removeEntity(GHOST_ID, Entity.RemovalReason.DISCARDED);
            ghost = null;
        }

        if (ghostEnabled && showBox && s != null) {
            Vec3 p = s.pos();
            pendingBox = new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 1.8, p.z + 0.3);
        } else {
            pendingBox = null;
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

    // ---------------- ghost as a mob ----------------

    /**
     * Ghost shape = mob: a second copy of your chosen mob at your delayed
     * position. It is invisible but glowing, which vanilla draws as a coloured
     * outline only (mobs can't be faded like the player ghost can).
     */
    private void updateMobGhost(Minecraft mc, ClientLevel level, Player me, Sample s, float partial) {
        EntityType<?> type = resolveType(mobId);
        if (type == null) return;

        if (ghostMob == null || ghostMobType != type || ghostMobBaby != baby
                || ghostMob.level() != level || ghostMob.isRemoved()) {
            removeMobGhost(level);
            Entity e = type.create(level, EntitySpawnReason.LOAD);
            if (!(e instanceof LivingEntity le)) return;
            le.setId(GHOST_MOB_ID);
            le.setNoGravity(true);
            le.noPhysics = true;
            le.setInvisible(true);
            ModState.ghostAquatic = isAquatic(type);
            if (le instanceof Mob mob) mob.setBaby(baby);
            level.addEntity(le);
            ghostMob = le;
            ghostMobType = type;
            ghostMobBaby = baby;
            hasLastGhost = false;
            ModState.ghostMob = le;
        }

        LivingEntity g = ghostMob;
        AttributeInstance inst = g.getAttribute(Attributes.SCALE);
        if (inst != null && inst.getBaseValue() != mobScale) inst.setBaseValue(mobScale);

        Vec3 p = s.pos();
        poseMob(g, type, p, s.bodyYaw(), s.headYaw(), s.pitch());
        g.setPose(me.isCrouching() ? Pose.CROUCHING : Pose.STANDING);
        g.setOnGround(me.onGround() && !isFloaty(type));

        // First person: switch the outline off while the ghost is close enough to surround the camera.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        boolean hide = firstPerson && hideNearDistance > 0
            && p.distanceTo(me.getPosition(partial)) < hideNearDistance;
        g.setGlowingTag(ghostOutline && !hide);
        ModState.ghostOutlineRgb = ghostOutlineRgb;
    }

    private void removeMobGhost(ClientLevel level) {
        if (ghostMob != null && ghostMob.level() == level) {
            level.removeEntity(GHOST_MOB_ID, Entity.RemovalReason.DISCARDED);
        }
        ghostMob = null;
        ghostMobType = null;
        ModState.ghostMob = null;
        ModState.ghostAquatic = false;
    }

    /** Per tick: animate the ghost mob's legs and sync its gear. */
    private void tickGhostMob(Minecraft mc, Player me) {
        if (me == null || mc.level == null || ghostMob == null || ghostMob.level() != mc.level) {
            hasLastGhost = false;
            return;
        }
        LivingEntity g = ghostMob;
        if (hasLastGhost) {
            g.xo = lastGhostX;
            g.yo = lastGhostY;
            g.zo = lastGhostZ;
        } else {
            g.xo = g.getX();
            g.yo = g.getY();
            g.zo = g.getZ();
        }
        g.calculateEntityAnimation(false);
        keepSwimming(g);
        lastGhostX = g.getX();
        lastGhostY = g.getY();
        lastGhostZ = g.getZ();
        hasLastGhost = true;

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack want = showEquipment ? me.getItemBySlot(slot).copy() : ItemStack.EMPTY;
            if (!ItemStack.matches(g.getItemBySlot(slot), want)) {
                g.setItemSlot(slot, want);
            }
        }
    }

    // ---------------- mob model ----------------

    private void updateMob(Minecraft mc, ClientLevel level, Player me, float partial) {
        if (!mobEnabled) {
            removeDummy(level);
            return;
        }

        EntityType<?> type = resolveType(mobId);
        if (type == null) {
            removeDummy(level);
            return;
        }

        if (dummy == null || dummyType != type || dummyBaby != baby
                || dummy.level() != level || dummy.isRemoved()) {
            removeDummy(level);
            Entity e = type.create(level, EntitySpawnReason.LOAD);
            if (!(e instanceof LivingEntity le)) {
                mobEnabled = false;
                mc.gui.setOverlayMessage(Component.literal(
                    "Can't use " + mobId + " as a model. Mob model turned off."), false);
                return;
            }
            le.setId(DUMMY_ID);
            le.setNoGravity(true);
            le.noPhysics = true;
            ModState.dummyAquatic = isAquatic(type);
            if (le instanceof Mob mob) mob.setBaby(baby);
            applyScale(le);
            level.addEntity(le);
            dummy = le;
            dummyType = type;
            dummyBaby = baby;
            hasLastTick = false;
            ModState.dummy = le;
        }

        LivingEntity d = (LivingEntity) dummy;
        if (appliedScale != mobScale) applyScale(d);

        Vec3 p = me.getPosition(partial);
        float body = Mth.rotLerp(partial, me.yBodyRotO, me.yBodyRot);
        float head = Mth.rotLerp(partial, me.yHeadRotO, me.yHeadRot);
        float pitch = me.getViewXRot(partial);

        // Old == new turns off vanilla's 20 Hz smoothing, so it follows frame-exact positions.
        poseMob(d, type, p, body, head, pitch);

        d.setPose(me.isCrouching() ? Pose.CROUCHING : Pose.STANDING);
        // Swimmers and flyers are lifted off the ground and treated as airborne / in water.
        d.setOnGround(me.onGround() && !isFloaty(type));
        d.setSprinting(me.isSprinting());

        // Copy your arm swing so attacks animate.
        d.swinging = me.swinging;
        d.swingTime = me.swingTime;
        d.attackAnim = me.attackAnim;
        d.oAttackAnim = me.oAttackAnim;

        // First person: hide the stand-in so the camera isn't inside it.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        d.setInvisible(firstPerson);
        ModState.hideDummy = firstPerson; // some renderers (ender dragon) ignore invisibility

        ModState.hidePlayer = true;
    }

    private void applyScale(LivingEntity le) {
        AttributeInstance inst = le.getAttribute(Attributes.SCALE);
        if (inst != null) inst.setBaseValue(mobScale);
        appliedScale = mobScale;
    }

    private void removeDummy(ClientLevel level) {
        if (dummy != null && dummy.level() == level) {
            level.removeEntity(DUMMY_ID, Entity.RemovalReason.DISCARDED);
        }
        dummy = null;
        dummyType = null;
        ModState.dummy = null;
        ModState.hidePlayer = false;
        ModState.hideDummy = false;
        ModState.dummyAquatic = false;
    }

    private static EntityType<?> resolveType(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return null;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(ident).orElse(null);
    }

    /** All vanilla living mobs, sorted by name, built once from the current world. */
    private List<Entry> entries(ClientLevel level) {
        if (entries != null) return entries;
        List<Entry> list = new ArrayList<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(t);
            if (key == null || !"minecraft".equals(key.getNamespace())) continue;
            if (t == EntityType.PLAYER) continue;
            Entity e;
            try {
                e = t.create(level, EntitySpawnReason.LOAD);
            } catch (RuntimeException ex) {
                continue;
            }
            if (!(e instanceof LivingEntity)) continue;
            String name = t.getDescription().getString();
            list.add(new Entry(t, key.toString(), name, (name + " " + key.getPath()).toLowerCase()));
        }
        list.sort(Comparator.comparing(Entry::name));
        entries = list;
        return list;
    }

    private void selectMob(String id) {
        mobId = id;
        mobEnabled = true;
        saveConfig();
    }

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    /** Main menu: pick a feature. */
    private static class MainScreen extends Screen {
        private final ThirdPersonHandsClient m;

        MainScreen(ThirdPersonHandsClient m) {
            super(Component.literal("Third Person Hands"));
            this.m = m;
        }

        @Override
        protected void init() {
            int w = 200;
            int x = (this.width - w) / 2;
            int y = this.height / 2 - 48;

            int titleW = this.font.width(this.title);
            addRenderableWidget(new StringWidget((this.width - titleW) / 2, y - 24, titleW, 9, this.title, this.font));

            addRenderableWidget(Button.builder(handsText(), b -> {
                ModState.handsInThirdPerson = !ModState.handsInThirdPerson;
                m.saveConfig();
                b.setMessage(handsText());
            }).bounds(x, y, w, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Ping ghost settings..."),
                b -> Minecraft.getInstance().setScreen(new GhostScreen(m, this)))
                .bounds(x, y + 26, w, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Mob model..."),
                b -> Minecraft.getInstance().setScreen(new MobScreen(m, this)))
                .bounds(x, y + 52, w, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(x, y + 84, w, 20).build());
        }

        private static Component handsText() {
            return Component.literal("Hands in third person: " + (ModState.handsInThirdPerson ? "ON" : "OFF"));
        }
    }

    /** Ping ghost settings. */
    private static class GhostScreen extends Screen {
        private final ThirdPersonHandsClient m;
        private final Screen parent;

        GhostScreen(ThirdPersonHandsClient m, Screen parent) {
            super(Component.literal("Ping ghost"));
            this.m = m;
            this.parent = parent;
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(parent);
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
            toggle(left, y0 + row * 0, "Ghost", () -> m.ghostEnabled, v -> m.ghostEnabled = v);
            toggle(left, y0 + row * 1, "Faded model", () -> m.showModel, v -> m.showModel = v);
            toggle(left, y0 + row * 2, "Outline", () -> m.ghostOutline, v -> m.ghostOutline = v);
            toggle(left, y0 + row * 3, "Armor + items", () -> m.showEquipment, v -> m.showEquipment = v);
            toggle(left, y0 + row * 4, "Hitbox outline", () -> m.showBox, v -> m.showBox = v);
            addRenderableWidget(Button.builder(shapeText(), b -> {
                m.ghostAsMob = !m.ghostAsMob;
                m.saveConfig();
                b.setMessage(shapeText());
            }).bounds(left, y0 + row * 5, 150, 20).build());

            // Right column: numbers
            stepper(right, y0 + row * 0, "Extra delay", " ms", 5, -200, 400, 0,
                () -> m.extraDelayMs, v -> m.extraDelayMs = (int) Math.round(v));
            stepper(right, y0 + row * 1, "Fixed", " ms", 10, -1, 600, 0,
                () -> m.fixedDelayMs, v -> m.fixedDelayMs = (int) Math.round(v));
            stepper(right, y0 + row * 2, "Hide near", "", 0.25, 0.0, 5.0, 2,
                () -> m.hideNearDistance, v -> m.hideNearDistance = v);

            // Outline colour
            int ty = y0 + row * 3;
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

            String note = "Fixed -1 = real ping.  Mob shape = outline only, needs a mob picked.";
            int nw = this.font.width(note);
            addRenderableWidget(new StringWidget((this.width - nw) / 2, this.height - 44, nw, 9,
                Component.literal(note), this.font));

            addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
        }

        private Component shapeText() {
            return Component.literal("Ghost shape: " + (m.ghostAsMob ? "Mob" : "Player"));
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

    /** Mob picker: search, page through every vanilla mob, baby / gear / size options. */
    private static class MobScreen extends Screen {
        private final ThirdPersonHandsClient m;
        private final Screen parent;
        private List<Entry> all = List.of();
        private List<Entry> filtered = List.of();
        private final List<AbstractWidget> grid = new ArrayList<>();
        private String search = "";
        private int page = 0;
        private int cols, rows, cellW, gridX, gridY;
        private Button pageLabel;
        private Button enabledBtn;
        private Button babyBtn;
        private Button gearBtn;
        private Button scaleLabel;

        MobScreen(ThirdPersonHandsClient m, Screen parent) {
            super(Component.literal("Choose your model"));
            this.m = m;
            this.parent = parent;
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(parent);
        }

        @Override
        protected void init() {
            Minecraft mc = Minecraft.getInstance();
            all = mc.level == null ? List.of() : m.entries(mc.level);

            cols = Mth.clamp((this.width - 20) / 94, 2, 4);
            rows = Mth.clamp((this.height - 132) / 22, 2, 8);
            cellW = Math.min(92, (this.width - 20 - (cols - 1) * 4) / cols);
            int totalW = cols * cellW + (cols - 1) * 4;
            gridX = (this.width - totalW) / 2;
            gridY = 48;

            int titleW = this.font.width(this.title);
            addRenderableWidget(new StringWidget((this.width - titleW) / 2, 8, titleW, 9, this.title, this.font));

            EditBox box = new EditBox(this.font, gridX, 22, totalW, 20, Component.literal("Search"));
            box.setMaxLength(40);
            box.setHint(Component.literal("Search mobs..."));
            box.setValue(search);
            box.setResponder(s -> {
                search = s.trim().toLowerCase();
                page = 0;
                refresh();
            });
            addRenderableWidget(box);
            setInitialFocus(box);

            // Row A: page controls
            int rowA = this.height - 78;
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page--;
                refresh();
            }).bounds(gridX, rowA, 20, 20).build());
            pageLabel = Button.builder(Component.literal(""), b -> {})
                .bounds(gridX + 24, rowA, totalW - 48, 20).build();
            pageLabel.active = false;
            addRenderableWidget(pageLabel);
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page++;
                refresh();
            }).bounds(gridX + totalW - 20, rowA, 20, 20).build());

            // Row B: toggles
            int rowB = this.height - 52;
            int tw = (totalW - 8) / 3;
            enabledBtn = Button.builder(Component.literal(""), b -> {
                m.mobEnabled = !m.mobEnabled;
                m.saveConfig();
                refreshToggles();
            }).bounds(gridX, rowB, tw, 20).build();
            babyBtn = Button.builder(Component.literal(""), b -> {
                m.baby = !m.baby;
                m.saveConfig();
                refreshToggles();
            }).bounds(gridX + tw + 4, rowB, tw, 20).build();
            gearBtn = Button.builder(Component.literal(""), b -> {
                m.mobCopyGear = !m.mobCopyGear;
                m.saveConfig();
                refreshToggles();
            }).bounds(gridX + (tw + 4) * 2, rowB, tw, 20).build();
            addRenderableWidget(enabledBtn);
            addRenderableWidget(babyBtn);
            addRenderableWidget(gearBtn);

            // Row C: size, and Back
            int rowC = this.height - 28;
            addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                m.mobScale = Math.max(0.25, m.mobScale - 0.25);
                m.saveConfig();
                refreshToggles();
            }).bounds(gridX, rowC, 20, 20).build());
            scaleLabel = Button.builder(Component.literal(""), b -> {})
                .bounds(gridX + 22, rowC, 86, 20).build();
            scaleLabel.active = false;
            addRenderableWidget(scaleLabel);
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                m.mobScale = Math.min(4.0, m.mobScale + 0.25);
                m.saveConfig();
                refreshToggles();
            }).bounds(gridX + 110, rowC, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(gridX + 138, rowC, totalW - 138, 20).build());

            refresh();
        }

        private void refreshToggles() {
            enabledBtn.setMessage(Component.literal("Model: " + (m.mobEnabled ? "ON" : "OFF")));
            babyBtn.setMessage(Component.literal("Baby: " + (m.baby ? "ON" : "OFF")));
            gearBtn.setMessage(Component.literal("My gear: " + (m.mobCopyGear ? "ON" : "OFF")));
            scaleLabel.setMessage(Component.literal("Size: " + String.format("%.2f", m.mobScale) + "x"));
        }

        private void refresh() {
            for (AbstractWidget w : grid) removeWidget(w);
            grid.clear();

            filtered = new ArrayList<>();
            for (Entry e : all) {
                if (search.isEmpty() || e.lower().contains(search)) filtered.add(e);
            }

            int per = cols * rows;
            int pages = Math.max(1, (filtered.size() + per - 1) / per);
            page = Mth.clamp(page, 0, pages - 1);

            for (int i = 0; i < per; i++) {
                int idx = page * per + i;
                if (idx >= filtered.size()) break;
                Entry e = filtered.get(idx);
                boolean selected = e.id().equals(m.mobId);
                Component label = selected
                    ? Component.literal(e.name()).withStyle(ChatFormatting.GREEN)
                    : Component.literal(e.name());
                int cx = gridX + (i % cols) * (cellW + 4);
                int cy = gridY + (i / cols) * 22;
                Button b = Button.builder(label, btn -> {
                    m.selectMob(e.id());
                    refresh();
                }).bounds(cx, cy, cellW, 20).build();
                grid.add(addRenderableWidget(b));
            }

            pageLabel.setMessage(Component.literal(
                "Page " + (page + 1) + "/" + pages + "   (" + filtered.size() + " mobs)"));
            refreshToggles();
        }
    }

    // ------------------------------------------------------------------
    // Config file
    // ------------------------------------------------------------------

    private void saveConfig() {
        try {
            Properties p = new Properties();
            p.setProperty("handsInThirdPerson", String.valueOf(ModState.handsInThirdPerson));
            p.setProperty("enabled", String.valueOf(ghostEnabled));
            p.setProperty("showModel", String.valueOf(showModel));
            p.setProperty("showBox", String.valueOf(showBox));
            p.setProperty("showEquipment", String.valueOf(showEquipment));
            p.setProperty("hideNearDistance", String.valueOf(hideNearDistance));
            p.setProperty("ghostOutline", String.valueOf(ghostOutline));
            p.setProperty("ghostOutlineColorHex", String.format("%06X", ghostOutlineRgb));
            p.setProperty("extraDelayMs", String.valueOf(extraDelayMs));
            p.setProperty("fixedDelayMs", String.valueOf(fixedDelayMs));
            p.setProperty("ghostAsMob", String.valueOf(ghostAsMob));
            p.setProperty("mobEnabled", String.valueOf(mobEnabled));
            p.setProperty("mob", mobId);
            p.setProperty("baby", String.valueOf(baby));
            p.setProperty("mobCopyGear", String.valueOf(mobCopyGear));
            p.setProperty("mobScale", String.valueOf(mobScale));
            p.setProperty("hoverHeight", String.valueOf(hoverHeight));
            try (OutputStream out = Files.newOutputStream(configPath)) {
                p.store(out, "Third Person Hands. enabled = ping ghost. fixedDelayMs=-1 means use your ping.");
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
            ModState.handsInThirdPerson = Boolean.parseBoolean(p.getProperty("handsInThirdPerson", "true").trim());
            ghostEnabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            showModel = Boolean.parseBoolean(p.getProperty("showModel", "true").trim());
            showBox = Boolean.parseBoolean(p.getProperty("showBox", "false").trim());
            showEquipment = Boolean.parseBoolean(p.getProperty("showEquipment", "false").trim());
            hideNearDistance = Double.parseDouble(p.getProperty("hideNearDistance", "1.0").trim());
            ghostOutline = Boolean.parseBoolean(p.getProperty("ghostOutline", "true").trim());
            ghostOutlineRgb = (int) (Long.parseLong(p.getProperty("ghostOutlineColorHex", "40C0FF").trim(), 16) & 0xFFFFFF);
            extraDelayMs = Integer.parseInt(p.getProperty("extraDelayMs", "0").trim());
            fixedDelayMs = Integer.parseInt(p.getProperty("fixedDelayMs", "-1").trim());
            ghostAsMob = Boolean.parseBoolean(p.getProperty("ghostAsMob", "false").trim());
            mobEnabled = Boolean.parseBoolean(p.getProperty("mobEnabled", "false").trim());
            String mob = p.getProperty("mob", "minecraft:zombie").trim().toLowerCase();
            mobId = mob.contains(":") ? mob : "minecraft:" + mob;
            baby = Boolean.parseBoolean(p.getProperty("baby", "false").trim());
            mobCopyGear = Boolean.parseBoolean(p.getProperty("mobCopyGear", "false").trim());
            mobScale = Mth.clamp(Double.parseDouble(p.getProperty("mobScale", "1.0").trim()), 0.25, 4.0);
            hoverHeight = Mth.clamp(Double.parseDouble(p.getProperty("hoverHeight", "1.25").trim()), 0.0, 5.0);
        } catch (IOException | NumberFormatException ignored) {
            // keep previous values if the file is unreadable or malformed
        }
    }
}
