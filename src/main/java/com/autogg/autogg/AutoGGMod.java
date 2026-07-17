package com.autogg.autogg;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AutoGGMod implements ModInitializer, ClientModInitializer {
    public static final String MOD_ID = "autogg";

    private static final List<String> DEFAULT_TRIGGERS = List.of(
            "\uD83C\uDFC6",
            "Winner(s):",
            "First to:",
            "Match Report",
            "Match Log",
            "Game Log",
            "Match Summary",
            "Game Over!");
    private static final String DEFAULT_RESPONSE = "gg";
    private static final long DEFAULT_COOLDOWN_MS = 5000L;

    // Live config: populated from autogg.properties at startup, mutated by the
    // in-game config screen, written back to disk on save.
    private List<String> triggers = new ArrayList<>(DEFAULT_TRIGGERS);
    private String responseMessage = DEFAULT_RESPONSE;
    private long cooldownMs = DEFAULT_COOLDOWN_MS;

    // Identity-based set of ChatHudLine references we've already processed.
    private final Set<Object> knownRefs = Collections.newSetFromMap(new IdentityHashMap<>());
    private long lastFireTime = 0L;
    // Tracked so we can skip our own server-echo of responseMessage.
    private String lastSentText = "";

    // Cached after first load/persist so the same file we read is written to.
    private Path configFilePath = null;

    private KeyBinding openConfigKey;

    @Override
    public void onInitialize() {
        System.out.println("[AutoGG] onInitialize entered");
        loadConfig();
        ClientTickEvents.START_WORLD_TICK.register(this::onTick);
    }

    @Override
    public void onInitializeClient() {
        // Default keybind F8 — non-conflicting with movement/chat/inventory.
        // Remappable via Options -> Controls (MISC category).
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autogg.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                KeyBinding.Category.MISC));
        // END_CLIENT_TICK fires every client tick (including on the title
        // menu), so the user can open the screen anywhere.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openConfigKey == null) return;
            while (openConfigKey.wasPressed()) {
                client.setScreen(new AutoGGConfigScreen(client.currentScreen));
            }
        });
    }

    private void onTick(ClientWorld world) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.isInSingleplayer()) return;

            ChatHud chatHud = (mc.inGameHud == null) ? null : mc.inGameHud.getChatHud();
            if (chatHud == null) return;

            List<?> messages = findMessagesList(chatHud);
            if (messages == null) return;

            int size = messages.size();
            for (int i = 0; i < size; i++) {
                Object line = messages.get(i);
                if (line == null) continue;
                if (knownRefs.contains(line)) break;
                knownRefs.add(line);

                String text = extractText(line);
                if (text.isEmpty()) continue;
                if (isOurEcho(text)) {
                    lastSentText = "";
                    continue;
                }

                String lower = text.toLowerCase(Locale.ROOT);
                for (String trig : triggers) {
                    if (lower.contains(trig.toLowerCase(Locale.ROOT))) {
                        long now = System.currentTimeMillis();
                        if (now - lastFireTime >= cooldownMs) {
                            if (send(mc, responseMessage)) {
                                lastFireTime = now;
                                lastSentText = responseMessage;
                                System.out.println("[AutoGG] matched \"" + trig
                                        + "\" -> sent \"" + responseMessage + "\"");
                            }
                        }
                        break;
                    }
                }
            }

            // Trim refs that scrolled out of the visible window. Collect stale
            // refs first, then removeAll outside the iteration to avoid
            // ConcurrentModificationException.
            Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object line : messages) if (line != null) current.add(line);
            Set<Object> stale = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object ref : knownRefs) if (!current.contains(ref)) stale.add(ref);
            knownRefs.removeAll(stale);
        } catch (Throwable ignored) {
            // never let a tick throw
        }
    }

    private boolean isOurEcho(String text) {
        if (lastSentText.isEmpty()) return false;
        String trimmed = text.trim();
        return trimmed.equalsIgnoreCase(lastSentText)
                || trimmed.endsWith(" " + lastSentText);
    }

    private static boolean send(MinecraftClient mc, String msg) {
        if (msg == null || msg.isEmpty() || mc.player == null) return false;
        ClientPlayNetworkHandler nh = mc.player.networkHandler;
        if (nh == null) return false;
        nh.sendChatMessage(msg);
        return true;
    }

    private void loadConfig() {
        File cfg = resolveConfigFile();
        try {
            if (cfg.exists()) {
                List<String> parsedTriggers = new ArrayList<>(DEFAULT_TRIGGERS);
                String parsedResponse = DEFAULT_RESPONSE;
                long parsedCooldown = DEFAULT_COOLDOWN_MS;
                // Read as UTF-8 explicitly so the default trophy emoji (and
                // any user-entered Unicode) survives on platforms whose
                // platform-default charset is not UTF-8 (e.g. Windows).
                for (String raw : Files.readAllLines(cfg.toPath(), StandardCharsets.UTF_8)) {
                    String l = raw.trim();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    int eq = l.indexOf('=');
                    if (eq <= 0) continue;
                    String key = l.substring(0, eq).trim();
                    String val = l.substring(eq + 1).trim();
                    if ("triggers".equalsIgnoreCase(key)) {
                        parsedTriggers.clear();
                        for (String t : val.split(",")) {
                            String tt = t.trim();
                            if (!tt.isEmpty()) parsedTriggers.add(tt);
                        }
                    } else if ("response".equalsIgnoreCase(key)) {
                        parsedResponse = val;
                    } else if ("cooldownMs".equalsIgnoreCase(key)) {
                        try { parsedCooldown = Long.parseLong(val); } catch (NumberFormatException nfe) { /* keep default */ }
                    }
                }
                triggers = parsedTriggers;
                responseMessage = parsedResponse;
                cooldownMs = parsedCooldown;
            } else {
                // First run: write the defaults so the user can find/edit it.
                persistConfig();
            }
        } catch (Throwable t) {
            System.out.println("[AutoGG] config load failed: " + t);
        }
        System.out.println("[AutoGG] config loaded: triggers=" + triggers
                + ", response=\"" + responseMessage + "\", cooldown=" + cooldownMs + "ms");
    }

    private void persistConfig() {
        if (configFilePath == null) {
            configFilePath = resolveConfigFile().toPath();
        }
        try {
            File cfg = configFilePath.toFile();
            File parent = cfg.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            // Write as UTF-8 explicitly so Unicode (e.g. trophy emoji) survives
            // round-trips on platforms whose default charset is not UTF-8.
            try (java.io.Writer w = Files.newBufferedWriter(cfg.toPath(), StandardCharsets.UTF_8)) {
                w.write("# AutoGG configuration\n");
                w.write("# Edit in-game via the AutoGG config screen (default key: F8).\n");
                w.write("# Changes are applied immediately and saved when you close the screen.\n");
                w.write("# Comma-separated substring patterns. A chat line matches if it\n");
                w.write("# contains any of these (case-insensitive substring search).\n");
                w.write("triggers=" + String.join(",", triggers) + "\n");
                w.write("# Message the mod sends in chat when a trigger fires.\n");
                w.write("response=" + responseMessage + "\n");
                w.write("# Minimum milliseconds between sends to avoid spam.\n");
                w.write("cooldownMs=" + cooldownMs + "\n");
            }
        } catch (Throwable t) {
            System.out.println("[AutoGG] config save failed: " + t);
        }
    }

    private File resolveConfigFile() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            return gameDir.resolve("config").resolve("autogg.properties").toFile();
        } catch (Throwable t) {
            return new File("./config/autogg.properties");
        }
    }

    /** Parse the raw text-field contents, replace in-memory config, and
     *  persist only if anything actually changed.
     *
     *  Robust to bad input. Critically, we DO NOT silently substitute the
     *  hard-coded {@link #DEFAULT_TRIGGERS} when the triggers field is empty.
     *  That used to overwrite the user's custom config whenever they opened
     *  the menu and closed it without edits (e.g. ESC). If the user clears
     *  the field we instead keep whatever was already in memory; the only
     *  place defaults are written is the very first run inside
     *  {@link #loadConfig}.
     *
     *  Live-update: the in-memory fields touched here are the same ones read
     *  by {@link #onTick} every tick, so new triggers take effect immediately
     *  without a restart. */
    void applyConfigFromText(String triggersText, String responseText, String cooldownText) {
        List<String> parsed = new ArrayList<>();
        if (triggersText != null) {
            for (String t : triggersText.split(",")) {
                String tt = t.trim();
                if (!tt.isEmpty()) parsed.add(tt);
            }
        }
        // Empty triggers field -> keep what's already in memory; do NOT reset
        // to DEFAULT_TRIGGERS. The previous behaviour silently nuked the
        // user's config for any case where the trimmed field came back empty.
        final boolean triggersProvided = !parsed.isEmpty();

        long cd = cooldownMs; // default to current in-memory value, not the global default
        if (cooldownText != null) {
            String trimmed = cooldownText.trim();
            if (!trimmed.isEmpty()) {
                try {
                    long parsedCd = Long.parseLong(trimmed);
                    if (parsedCd < 0) parsedCd = 0L;
                    cd = parsedCd;
                } catch (NumberFormatException nfe) { /* keep current */ }
            }
        }

        final String newResponse = (responseText == null || responseText.isEmpty())
                ? responseMessage   // empty -> keep current; do NOT reset to default
                : responseText;

        final boolean triggersChanged  = triggersProvided && !parsed.equals(triggers);
        final boolean responseChanged  = !newResponse.equals(responseMessage);
        final boolean cooldownChanged  = cd != cooldownMs;

        if (triggersChanged)  triggers = parsed;
        if (responseChanged)  responseMessage = newResponse;
        if (cooldownChanged)  cooldownMs = cd;
        // Reset transient state so a fresh response/cooldown take full effect.
        lastSentText = "";
        lastFireTime = 0L;

        if (triggersChanged || responseChanged || cooldownChanged) {
            persistConfig();
            System.out.println("[AutoGG] config updated via GUI: triggers=" + triggers
                    + ", response=\"" + responseMessage + "\", cooldown=" + cooldownMs + "ms");
        } else {
            // Nothing changed -> skip the disk write entirely so opening and
            // closing the menu is a no-op (no mtime churn on the file).
            System.out.println("[AutoGG] config unchanged (triggers=" + triggers
                    + ", response=\"" + responseMessage + "\", cooldown=" + cooldownMs + "ms"
                    + ") -- skipping save to avoid overwriting custom config.");
        }
    }

    /** Vanilla screen that lets the user edit triggers / response / cooldown
     *  from in-game and writes the result back to the config file. Always
     *  saves on ESC and on Done; partial edits fall back to defaults per
     *  field via applyConfigFromText. */
    class AutoGGConfigScreen extends Screen {
        private final Screen parentScreen;
        private TextFieldWidget triggersField;
        private TextFieldWidget responseField;
        private TextFieldWidget cooldownField;

        AutoGGConfigScreen(Screen parent) {
            super(Text.translatable("screen.autogg.title"));
            this.parentScreen = parent;
        }

        @Override
        protected void init() {
            super.init();
            int cx = this.width / 2;

            triggersField = new TextFieldWidget(
                    this.textRenderer, cx - 200, 60, 400, 20,
                    Text.translatable("screen.autogg.triggers"));
            triggersField.setMaxLength(1000);
            triggersField.setText(String.join(",", AutoGGMod.this.triggers));
            this.addDrawableChild(triggersField);

            responseField = new TextFieldWidget(
                    this.textRenderer, cx - 200, 110, 400, 20,
                    Text.translatable("screen.autogg.response"));
            responseField.setMaxLength(256);
            responseField.setText(AutoGGMod.this.responseMessage);
            this.addDrawableChild(responseField);

            cooldownField = new TextFieldWidget(
                    this.textRenderer, cx - 60, 160, 120, 20,
                    Text.translatable("screen.autogg.cooldown"));
            cooldownField.setMaxLength(12);
            cooldownField.setText(String.valueOf(AutoGGMod.this.cooldownMs));
            this.addDrawableChild(cooldownField);

            this.addDrawableChild(ButtonWidget.builder(
                    Text.translatable("gui.done"),
                    btn -> saveFromFieldsAndClose())
                    .dimensions(cx - 100, 210, 200, 20)
                    .build());
        }

        private void saveFromFieldsAndClose() {
            saveFromFields();
            if (this.client != null) {
                this.client.setScreen(this.parentScreen);
            }
        }

        private void saveFromFields() {
            AutoGGMod.this.applyConfigFromText(
                    triggersField == null ? "" : triggersField.getText(),
                    responseField == null ? "" : responseField.getText(),
                    cooldownField == null ? "" : cooldownField.getText());
        }

        @Override
        public void close() {
            // Save even if the user presses ESC; otherwise closing without
            // saving would silently discard the changes.
            saveFromFields();
            if (this.client != null) {
                this.client.setScreen(this.parentScreen);
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // In Yarn 1.21.x the superclass render path paints the background
            // blur AND iterates the drawables list in its own pass. If we draw
            // labels / call renderBackground first and call super.render LAST,
            // super.render's pass overwrites our labels and (because of how it
            // resets the draw context state) the children don't appear
            // alongside them — the symptom is "blur with no UI elements".
            //
            // The fix is to let super.render paint the background AND the
            // children first, then draw our labels (title + field captions +
            // hint) on top in the same frame.
            super.render(context, mouseX, mouseY, delta);
            int cx = this.width / 2;
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 15, 0xFFFFFFFF);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("screen.autogg.triggers_label"), cx - 200, 42, 0xFFA0A0A0);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("screen.autogg.response_label"), cx - 200, 92, 0xFFA0A0A0);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("screen.autogg.cooldown_label"), cx - 200, 142, 0xFFA0A0A0);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("screen.autogg.hint"), cx, 250, 0xFF808080);
        }
    }

    /**
     * Vanilla 1.21.11 {@code ChatHud} has multiple {@code List<?>} fields
     * (the live chat display, a trimmed version, and a typed-message string
     * history used for up-arrow recall). The underlying reference can also be
     * replaced on a server switch. {@code getDeclaredFields()} order is stable
     * within a JVM, so a consistent field is picked across ticks. The
     * {@link #isStringHistory} filter is essential: without it the reflection
     * deterministically picks the typed-message history list (whose elements
     * are {@code String}), so MATCH REPORT lines never reach
     * {@link #extractText} and the mod would never fire.
     */
    private static List<?> findMessagesList(ChatHud chatHud) {
        try {
            for (Field f : chatHud.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(chatHud);
                if (!(v instanceof List<?>)) continue;
                List<?> list = (List<?>) v;
                if (isStringHistory(list)) continue;
                for (Object element : list) {
                    if (element == null) continue;
                    if (!extractText(element).isEmpty()) return list;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isStringHistory(List<?> list) {
        if (list.isEmpty()) return false;
        for (Object element : list) {
            if (element == null) continue;
            return element.getClass() == String.class;
        }
        return false;
    }

    /**
     * ChatHudLine.content() returns an {@code OrderedText} in Yarn 1.21.11.
     * Try OrderedText.getString() first; fall back to walking the
     * {@code CharacterVisitor} via a proxy that accumulates code points; last
     * resort toString(). Always returns a non-null String; callers can use
     * isEmpty() to skip blank lines.
     */
    private static String extractText(Object line) {
        if (line == null) return "";
        Object ord = null;
        try {
            ord = line.getClass().getMethod("content").invoke(line);
        } catch (Throwable ignored) {
            for (Field f : line.getClass().getDeclaredFields()) {
                Class<?> t = f.getType();
                if (t == String.class) {
                    f.setAccessible(true);
                    try { return (String) f.get(line); } catch (Throwable ig) {}
                }
                if (ord == null && !t.isPrimitive() && !t.isArray()
                        && !List.class.isAssignableFrom(t)
                        && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    try { ord = f.get(line); } catch (Throwable ig) {}
                }
            }
            if (ord == null) return line.toString();
        }
        if (ord == null) return line.toString();
        try {
            return (String) ord.getClass().getMethod("getString").invoke(ord);
        } catch (Throwable ignored) {}
        try {
            Class<?> vc = Class.forName("net.minecraft.text.CharacterVisitor");
            if (vc != null) {
                Method accept = ord.getClass().getMethod("accept", vc);
                final StringBuilder buf = new StringBuilder();
                Object visitor = Proxy.newProxyInstance(
                        vc.getClassLoader(), new Class<?>[]{vc},
                        (proxy, mth, args) -> {
                            if ("accept".equals(mth.getName()) && args != null
                                    && args.length > 0 && args[0] != null) {
                                int cp = (int) args[0];
                                if (cp > 0) buf.appendCodePoint(cp);
                            }
                            return null;
                        });
                accept.invoke(ord, visitor);
                if (buf.length() > 0) return buf.toString();
            }
        } catch (Throwable ignored) {}
        return ord.toString();
    }
}
