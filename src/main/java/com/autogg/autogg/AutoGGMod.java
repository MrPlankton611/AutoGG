package com.autogg.autogg;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AutoGGMod implements ModInitializer {
    // Hardcoded chat-line substrings that auto-fire the response. Whatever the
    // server prints, if any new line contains one of these (case-insensitive
    // substring), we send "gg" through the player's network handler. No config
    // file, no GUI, no keybind — edit this list and rebuild to change it.
    private static final List<String> TRIGGERS = List.of(
            "\uD83C\uDFC6",
            "Winner(s):",
            "First to:",
            "Match Report",
            "Match Log",
            "Game Log",
            "Match Summary",
            "Game Over!");
    private static final String RESPONSE = "gg";
    // Minimum milliseconds between sends. A multi-line match summary can fire
    // several triggers in the same network tick; this prevents a flood.
    private static final long COOLDOWN_MS = 5000L;

    // Identity-based set of ChatHudLine references we've already processed.
    // ChatHud prepends new lines, so the newest is at index 0; walking from
    // there forward and stopping at the first already-known reference bounds
    // each tick to brand-new lines and avoids re-firing every tick on the
    // visible backlog. Identity (not equals) is required because ChatHudLine
    // records with the same content but different instances would otherwise
    // be "equal" and we'd never advance knownRefs.
    private final Set<Object> knownRefs = Collections.newSetFromMap(new IdentityHashMap<>());
    private long lastFireTime = 0L;
    // Tracked so we can skip our own server-echo of RESPONSE.
    private String lastSentText = "";

    @Override
    public void onInitialize() {
        System.out.println("[AutoGG] loaded — watching chat, will send \"" + RESPONSE + "\" on trigger");
        ClientTickEvents.START_WORLD_TICK.register(this::onTick);
    }

    private void onTick(ClientWorld world) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.isInSingleplayer()) return;

            ChatHud chatHud = (mc.inGameHud == null) ? null : mc.inGameHud.getChatHud();
            if (chatHud == null) return;

            List<?> messages = findMessagesList(chatHud);
            if (messages == null) return;

            // Walk from index 0 (newest) forward. Stop the moment we hit a ref
            // we already know — anything before it (in 0..i-1) is freshly
            // added by the chat HUD this tick.
            int size = messages.size();
            for (int i = 0; i < size; i++) {
                Object line = messages.get(i);
                if (line == null) continue;
                if (knownRefs.contains(line)) break;
                knownRefs.add(line);

                String text = extractText(line);
                if (text.isEmpty()) continue;

                // Server echoes our own sends as chat lines; suppress them.
                if (isOurEcho(text)) {
                    lastSentText = "";
                    continue;
                }

                matchAndFire(mc, text);
            }

            // Trim refs that scrolled out of the visible window so knownRefs
            // doesn't grow without bound over a long session. Collect stale
            // refs first, then removeAll, so we don't mutate knownRefs while
            // iterating its entry set (avoids ConcurrentModificationException).
            Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object line : messages) if (line != null) current.add(line);
            Set<Object> stale = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object ref : knownRefs) if (!current.contains(ref)) stale.add(ref);
            knownRefs.removeAll(stale);
        } catch (Throwable ignored) {
            // never let a tick throw
        }
    }

    private void matchAndFire(MinecraftClient mc, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String trig : TRIGGERS) {
            if (lower.contains(trig.toLowerCase(Locale.ROOT))) {
                long now = System.currentTimeMillis();
                if (now - lastFireTime >= COOLDOWN_MS) {
                    if (send(mc, RESPONSE)) {
                        lastFireTime = now;
                        lastSentText = RESPONSE;
                        System.out.println("[AutoGG] matched \"" + trig + "\" -> sent \"" + RESPONSE + "\"");
                    }
                }
                return;
            }
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
