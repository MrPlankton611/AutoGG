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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class AutoGGMod implements ModInitializer {
    public static final String MOD_ID = "autogg";

    private static final List<String> DEFAULT_TRIGGERS = List.of("\uD83C\uDFC6", "Winner(s):", "First to:", "Match Report", "Match Log", "Game Log", "Match Summary", "Game Over!");
    private static final String DEFAULT_RESPONSE = "gg";
    private static final long DEFAULT_COOLDOWN_MS = 5000L;

    private List<String> triggers = DEFAULT_TRIGGERS;
    private String responseMessage = DEFAULT_RESPONSE;
    private long cooldownMs = DEFAULT_COOLDOWN_MS;

    private boolean primed = false;
    // Identity-based set of ChatHudLine element references we've already processed.
    // Minecraft's ChatHud prepends via `messages.add(0, ...)` so newest lines are at
    // index 0; scanning from 0 forward and stopping at the first already-known ref
    // works regardless of prepend/append. Tracking references (not size) also handles
    // the chat-buffer cap plateau: when size is pinned at 100, new prepended lines
    // still bump the front and the IdentityHashMap sees them as new.
    private final Set<Object> knownRefs = Collections.newSetFromMap(new IdentityHashMap<>());
    // Defensive cap; in practice the trim keeps us well under this.
    private static final int MAX_KNOWN_REFS = 256;
    private long lastFireTime = 0L;
    private String lastSentText = "";

    // Player-UUID snapshot used to detect world / player swap and reset knownRefs.
    private UUID lastPlayerUuid = null;
    // Last messages-list reference we processed. A change in this reference means the
    // ChatHud handed us a brand-new list (server switch, mod re-init, etc.); we treat
    // that as a chat reset and re-prime instead of firing on the fresh backlog.
    private List<?> lastPickedList = null;

    // Rate-limit for the "scanned, no matching trigger" diagnostic so it doesn't spam.
    private long lastScanLogMs = 0L;
    private static final long SCAN_LOG_INTERVAL_MS = 10_000L;

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
                if (messages == null) return;

                // World / player change detection: clear all state so the new chat hud
                // doesn't fire on already-displayed backlog from the previous session.
                UUID currentUuid = mc.player.getUuid();
                if (lastPlayerUuid != null && !lastPlayerUuid.equals(currentUuid)) {
                    primed = false;
                    knownRefs.clear();
                    lastPickedList = null;
                    System.out.println("[AutoGG] player changed; resetting chat watcher");
                }
                lastPlayerUuid = currentUuid;

                // Direct chat-reset detection: if the messages-list reference we
                // picked this tick differs from the one we saw last tick, the
                // ChatHud just handed us a brand-new list (server switch, mid-
                // session mod reset, chat-cleared-and-repopulated on tab rejoin).
                // Re-prime: seed knownRefs from the new list and skip the main
                // scan so we don't fire on the legacy-looking backlog. This is
                // a direct, robust signal; the in-place clear-and-refill case is
                // rare enough in normal play that guarding against it with a magic
                // size threshold would introduce more false positives than it
                // prevents false negatives.
                if (primed && lastPickedList != null && lastPickedList != messages) {
                    System.out.println("[AutoGG] chat list reference changed; re-priming without firing");
                    primed = false;
                    knownRefs.clear();
                }
                lastPickedList = messages;

                if (!primed) {
                    primed = true;
                    knownRefs.clear();
                    for (Object line : messages) {
                        if (line != null) knownRefs.add(line);
                    }
                    if (lastPickedList == null || lastPickedList == messages) {
                        System.out.println("[AutoGG] chat polling started; " + messages.size() + " lines buffered");
                    }
                    return;
                }

                int size = messages.size();

                // Defensive cap to bound knownRefs regardless of whether the trim path
                // was reached (e.g. if newCount > 0 logic ever regresses).
                if (knownRefs.size() > MAX_KNOWN_REFS) {
                    knownRefs.clear();
                    for (Object line : messages) {
                        if (line != null) knownRefs.add(line);
                    }
                }

                // Walk from the front (newest = index 0 due to ChatHud's prepend).
                // Stop the moment we hit a reference that's already in knownRefs.
                int newCount = 0;
                int firedCount = 0;
                for (int i = 0; i < size; i++) {
                    Object line = messages.get(i);
                    if (line == null) continue;
                    if (knownRefs.contains(line)) {
                        // From here on, all refs were in the previous frame. Anything
                        // before this index (0..i-1) is freshly added.
                        break;
                    }

                    knownRefs.add(line);
                    newCount++;

                    String text = extractText(line);
                    if (text.isEmpty()) continue;

                    // Self-loop guard: server typically echoes our send as a chat line.
                    if (isOurEcho(text)) {
                        lastSentText = ""; // consumed; don't suppress later lines
                        continue;
                    }

                    String lowerText = text.toLowerCase(Locale.ROOT);
                    for (String trig : triggers) {
                        if (lowerText.contains(trig.toLowerCase(Locale.ROOT))) {
                            long now = System.currentTimeMillis();
                            if (now - lastFireTime >= cooldownMs) {
                                sendChat(mc, responseMessage);
                                System.out.println("[AutoGG] matched '" + trig + "' -> sent '" + responseMessage + "'");
                                lastFireTime = now;
                                lastSentText = responseMessage;
                                firedCount++;
                            }
                            break;
                        }
                    }
                }

                // Trim knownRefs to the currently-visible window whenever anything
                // changed. Amortized: only on ticks where new lines actually arrived.
                // IMPORTANT: membership test must be identity-based. List.contains
                // calls .equals(), and ChatHudLine/MessagePair records with the same
                // content but different instances would be considered equal -- exactly
                // the bug class that the whole tracking scheme is meant to avoid.
                if (newCount > 0) {
                    Set<Object> currentSet = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Object line : messages) {
                        if (line != null) currentSet.add(line);
                    }
                    Set<Object> stale = null;
                    for (Object line : knownRefs) {
                        if (!currentSet.contains(line)) {
                            if (stale == null) stale = Collections.newSetFromMap(new IdentityHashMap<>());
                            stale.add(line);
                        }
                    }
                    if (stale != null) knownRefs.removeAll(stale);

                    long now = System.currentTimeMillis();
                    if (firedCount == 0 && now - lastScanLogMs >= SCAN_LOG_INTERVAL_MS) {
                        System.out.println("[AutoGG] " + newCount + " new chat line(s) scanned, no matching trigger");
                        lastScanLogMs = now;
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
     * Pick the chat-hud messages list on the runtime ChatHud every tick. Yarn 1.21.11
     * ChatHud has multiple List fields (`messages`, trimmedMessages, sent-message
     * history) and the underlying reference can be replaced on a server-switch.
     * getDeclaredFields() order is stable within a JVM, so the same field is picked
     * consistently across ticks. The most important filter is {@link
     * #isLikelyStringHistory}: without it the reflection deterministically picks
     * the typed-message history list (whose elements are {@code String}), which never
     * contains MATCH REPORT lines and so the mod would never trigger.
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
                if (isLikelyStringHistory(list)) continue;
                for (Object element : list) {
                    if (element == null) continue;
                    String s = extractText(element);
                    if (!s.isEmpty()) return list;
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Skip lists whose first non-null element is a {@code String}. Vanilla 1.21.11
     * ChatHud has a typed-message history list (the up-arrow recall buffer) holding
     * {@code String} entries. If we pick it, no MATCH REPORT line will ever pass
     * through {@link #extractText} triggers, so we treat String-typed lists as the
     * history list and look further. Empty lists are not flagged here so a freshly-
     * primed chat can still resolve to a chat-display list that just happens to be
     * empty at that moment.
     */
    private static boolean isLikelyStringHistory(List<?> list) {
        if (list.isEmpty()) return false;
        for (Object element : list) {
            if (element == null) continue;
            return element.getClass() == String.class;
        }
        return false;
    }

    /**
     * ChatHudLine.content() returns an OrderedText in Yarn 1.21.11. Try Text#getString()
     * first, fall back to walking OrderedText via a CharacterVisitor proxy that
     * accumulates code points. Last resort: toString(). Always returns a non-null
     * String; callers can use {@code text.isEmpty()} to skip blank lines.
     */
    private static String extractText(Object line) {
        if (line == null) return "";
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
                    w.write("triggers=\uD83C\uDFC6,Winner(s):,First to:,Match Report,Match Log,Game Log,Match Summary,Game Over!\n");
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
