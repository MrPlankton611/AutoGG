package com.autogg.autogg;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AutoGGMod implements ModInitializer {
    public static final String MOD_ID = "autogg";

    private static final List<String> DEFAULT_TRIGGERS = List.of("\uD83C\uDFC6", "Winner(s):", "First to:");
    private static final String DEFAULT_RESPONSE = "gg";
    private static final long DEFAULT_COOLDOWN_MS = 5000L;

    private List<String> triggers = DEFAULT_TRIGGERS;
    private String responseMessage = DEFAULT_RESPONSE;
    private long cooldownMs = DEFAULT_COOLDOWN_MS;

    private boolean primed = false;
    private int lastChatSize = 0;
    private long lastFireTime = 0L;
    private String lastSentText = "";

    private static boolean visitorClassResolved = false;
    private static Class<?> characterVisitorClass = null;

    @Override
    public void onInitialize() {
        System.out.println("[AutoGG] onInitialize entered");
        loadConfig();

        ClientTickEvents.START_WORLD_TICK.register(client -> {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc == null || mc.player == null || mc.isInSingleplayer()) return;

                ChatHud chatHud = (mc.inGameHud == null) ? null : mc.inGameHud.getChatHud();
                if (chatHud == null) return;

                List<?> messages = findMessagesList(chatHud);
                if (!primed) {
                    primed = true;
                    lastChatSize = (messages == null) ? 0 : messages.size();
                    System.out.println("[AutoGG] chat polling started; " + (messages == null ? "no list yet, will retry" : messages.size() + " lines buffered"));
                    return;
                }
                if (messages == null) return;
                int size = messages.size();
                if (size <= lastChatSize) {
                    lastChatSize = size;
                    return;
                }

                int since = lastChatSize;
                lastChatSize = size;
                for (int i = since; i < size; i++) {
                    Object line = messages.get(i);
                    String text = extractText(line);
                    if (text == null || text.isEmpty()) continue;

                    // Self-loop guard: server typically echoes our send as a chat line.
                    // Skip if the new line is exactly our send, or ends with " <our-send>".
                    // (Substring containment was too greedy — would suppress "Winner(s): gg").
                    if (isOurEcho(text)) {
                        lastSentText = ""; // consumed; don't suppress further legitimate lines
                        continue;
                    }

                    for (String trig : triggers) {
                        if (text.contains(trig)) {
                            long now = System.currentTimeMillis();
                            if (now - lastFireTime >= cooldownMs) {
                                sendChat(mc, responseMessage);
                                System.out.println("[AutoGG] matched '" + trig + "' -> sent '" + responseMessage + "'");
                                lastFireTime = now;
                                lastSentText = responseMessage;
                            }
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // never let a tick throw
            }
        });
    }

    private boolean isOurEcho(String text) {
        if (lastSentText.isEmpty()) return false;
        String trimmed = text.trim();
        if (trimmed.equalsIgnoreCase(lastSentText)) return true;
        if (trimmed.endsWith(" " + lastSentText)) return true;
        return false;
    }

    /**
     * Pick the chat messages list on the runtime ChatHud. Yarn 1.21.11 ChatHud has more
     * than one List field (visible `messages`, `trimmedMessages` (List<MessagePair>),
     * sent-message history). Both ChatHudLine and MessagePair expose a no-arg
     * `content()` method, so any pure signature-based selector can pick the wrong
     * list. We empirically pick whichever list's first non-null element yields a
     * non-empty text via `extractText(...)`. That selects only the list whose
     * elements are ChatHudLine (or otherwise readable).
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
                // Test that at least one element produces a non-empty text via our
                // normalizer. That empirically identifies readable chat lines.
                for (Object element : list) {
                    if (element == null) continue;
                    String s = extractText(element);
                    if (s != null && !s.isEmpty()) return list;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /**
     * ChatHudLine.content() returns an OrderedText in Yarn 1.21.11. Try Text#getString()
     * first, fall back to walking OrderedText via a CharacterVisitor proxy that
     * accumulates code points. Last resort: toString().
     */
    private static String extractText(Object line) {
        if (line == null) return null;
        Object ord = null;
        try {
            Method m = line.getClass().getMethod("content");
            ord = m.invoke(line);
        } catch (Throwable ignored) {
            for (Field f : line.getClass().getDeclaredFields()) {
                Class<?> t = f.getType();
                if (t == String.class) {
                    f.setAccessible(true);
                    try { return (String) f.get(line); } catch (Throwable ig) { /* keep looking */ }
                }
                if (ord == null && !t.isPrimitive() && !t.isArray() && !List.class.isAssignableFrom(t)
                        && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    try { ord = f.get(line); } catch (Throwable ig) { /* keep going */ }
                }
            }
            if (ord == null) return line.toString();
        }
        if (ord == null) return line.toString();

        try {
            Method gs = ord.getClass().getMethod("getString");
            return (String) gs.invoke(ord);
        } catch (Throwable ignored) { /* fall through */ }

        try {
            Class<?> vc = resolveCharacterVisitor();
            if (vc != null) {
                Method accept = ord.getClass().getMethod("accept", vc);
                final StringBuilder buf = new StringBuilder();
                Object visitor = Proxy.newProxyInstance(
                        vc.getClassLoader(),
                        new Class<?>[] { vc },
                        (proxy, mth, args) -> {
                            if (!"accept".equals(mth.getName())) return null;
                            if (args == null || args.length == 0 || args[0] == null) return null;
                            int cp = (int) args[0];
                            if (cp > 0) buf.appendCodePoint(cp);
                            return null;
                        }
                );
                accept.invoke(ord, visitor);
                if (buf.length() > 0) return buf.toString();
            }
        } catch (Throwable ignored) { /* fall through */ }

        return ord.toString();
    }

    private static Class<?> resolveCharacterVisitor() {
        if (visitorClassResolved) return characterVisitorClass;
        visitorClassResolved = true;
        try {
            characterVisitorClass = Class.forName("net.minecraft.text.CharacterVisitor");
        } catch (Throwable ignored) {
            characterVisitorClass = null;
        }
        return characterVisitorClass;
    }

    private static void sendChat(MinecraftClient mc, String msg) {
        if (msg == null || msg.isEmpty() || mc.player == null) return;
        ClientPlayNetworkHandler nh = mc.player.networkHandler;
        if (nh == null) return;
        nh.sendChatMessage(msg);
    }

    private void loadConfig() {
        File cfg;
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            cfg = gameDir.resolve("config").resolve("autogg.properties").toFile();
        } catch (Throwable t) {
            cfg = new File("./config/autogg.properties");
        }
        try {
            if (cfg.exists()) {
                List<String> parsedTriggers = new ArrayList<>(DEFAULT_TRIGGERS);
                String parsedResponse = DEFAULT_RESPONSE;
                long parsedCooldown = DEFAULT_COOLDOWN_MS;
                for (String raw : Files.readAllLines(cfg.toPath())) {
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
                triggers = Collections.unmodifiableList(parsedTriggers);
                responseMessage = parsedResponse;
                cooldownMs = parsedCooldown;
            } else {
                File parent = cfg.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileWriter w = new FileWriter(cfg)) {
                    w.write("# AutoGG configuration\n");
                    w.write("# Comma-separated substring patterns. A chat line matches if it contains\n");
                    w.write("# any of these (case-sensitive substring search).\n");
                    w.write("triggers=\uD83C\uDFC6,Winner(s):,First to:\n");
                    w.write("# Message the mod sends in chat when a trigger fires.\n");
                    w.write("response=gg\n");
                    w.write("# Minimum milliseconds between sends to avoid spam.\n");
                    w.write("cooldownMs=5000\n");
                }
            }
        } catch (Throwable t) {
            System.out.println("[AutoGG] config load failed: " + t);
        }
        System.out.println("[AutoGG] config loaded: triggers=" + triggers
                + ", response=\"" + responseMessage + "\", cooldown=" + cooldownMs + "ms, file=" + cfg.getAbsolutePath());
    }
}
