# AutoGG

A small **Fabric** mod for **Minecraft 1.21.11** that watches the chat HUD on
multiplayer servers, matches configurable trigger substrings (default: trophy
`🏆`, `Winner(s):`, `Match Report`, etc.), and sends a configurable response
(default `gg`) once per cooldown window when a match ends.

- Mod id: `autogg`
- Version: `1.0.0`
- License: GPL-3.0
- Java: 21
- Fabric Loader: `>= 0.16.9`
- Fabric API: `0.141.5+1.21.11`
- Yarn mappings: `1.21.11+build.1`

## Install (player)

1. Build (see below) or grab `build/libs/autogg-1.0.0.jar`.
2. Copy it into your Minecraft `mods/` folder
   (Windows: `%AppData%\.minecraft\mods\`).
3. Launch Minecraft 1.21.11 with Fabric Loader 0.19.3 (or newer 0.16.9+) and
   Fabric API installed.
4. The mod creates `config/autogg.properties` on first run with sensible
   defaults so nothing else needs to be done.

The mod only acts on multiplayer servers. Singleplayer is excluded by design.

## Configure

The config file lives at `<gameDir>/config/autogg.properties`. It is created
on first launch with the defaults below; edit it and relaunch to apply.

```properties
# Comma-separated substring patterns. A chat line matches (case-insensitive
# substring search) if it contains any of these tokens.
triggers=🏆,Winner(s):,First to:,Match Report,Match Log,Game Log,Match Summary,Game Over!

# Chat message the mod sends when a trigger fires.
response=gg

# Minimum milliseconds between sends — prevents spam if multiple triggers
# hit the same line or two server messages land within a tick.
cooldownMs=5000
```

Lines beginning with `#` are comments. Blank lines are ignored. Bad numeric
values for `cooldownMs` fall back to the default (5000).

### How matching works

- A `ChatHudLine` reference is added to the in-memory identity set the
  **first** time the chat HUD displays it.
- On later ticks, lines whose identity is not yet in the set are scanned.
- Each scanned line is checked against all `triggers` via
  `String.contains(..., Locale.ROOT)` (so `Winner(s):` matches regardless of
  case).
- The first matching trigger fires `response` through the player's network
  handler. The cooldown timer starts at that moment.
- A 5-second default cooldown prevents stacking on multi-line match summaries.

### Self-echo suppression

Server echoes your own chats back through the same chat HUD. The mod keeps a
short-lived "last sent" string and skips any incoming line whose trimmed text
equals or ends with that string, so `gg` from the mod never triggers
a second `gg` from the server echo of the first.

## Build from source

Requires JDK 21 on `PATH` (or pointed at by `org.gradle.java.home`).

### Recommended: cached Gradle 9.6.0 directly

The pinned Loom 1.17.14 + Gradle wrapper combo has a known
`CMD_LINE_ARGS: empty segment` failure on this project. Bypass the wrapper
and call the cached Gradle distribution directly:

```bash
"/c/Users/$USER/.gradle/wrapper/dists/gradle-9.6.0-bin"/*/gradle-9.6.0/bin/gradle.bat \
    --no-daemon build -x test
```

Output jar: `build/libs/autogg-1.0.0.jar` (and `build/devlibs/autogg-1.0.0-dev.jar`
which stays in the devlibs path — do **not** copy that one to `mods/`).

### Wrapper fix (optional)

To make `./gradlew.bat build` work from a fresh clone, edit
`gradle/wrapper/gradle-wrapper.properties` and pin `distributionUrl` to:

```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.4-bin.zip
```

Loom 1.17.14 supports Gradle 8.x cleanly on JDK 21, and the wrapper then
works without the `CMD_LINE_ARGS` workaround.

## How the chat HUD is read

`AutoGGMod.findMessagesList` walks `ChatHud.getClass().getDeclaredFields()` on
every tick and picks the first non-static `List<?>` whose first non-null
element is **not** a `java.lang.String`. The `String`-filter is essential:
vanilla 1.21.11 `ChatHud` carries a typed-message history list
(used for up-arrow recall of past `/msg` and commands) whose elements are
`String`. Without the filter the reflection deterministically picks that
list — `MATCH REPORT` lines never appear in it, so the mod never fires.

The chat HUD list reference is also cached across ticks; if `ChatHud`
hands the mod a brand-new list reference (server switch, mod reset,
backlog replay), the identity set is re-seeded from the new list and no
trigger fires on the freshly-seen backlog.

## Troubleshooting

- **Nothing fires on clear match-end lines** — confirm
  `config/autogg.properties` has the trigger you expect. The default list
  covers Hypixel-style and lunar-PvP-style match messages.
- **Triggers fire twice** — lower `cooldownMs` won't help (that's a floor
  not a ceiling). The self-echo suppression already drops server echoes of
  your own sends.
- **Build fails with `CMD_LINE_ARGS`** — use the cached Gradle 9.6 direct
  invocation above, or switch the wrapper distributionUrl to
  `gradle-8.10.4-bin.zip` as documented in *Build from source*.

## Project layout

```
src/main/java/com/autogg/autogg/AutoGGMod.java   single-file mod
src/main/resources/
    assets/autogg/lang/en_us.json                (empty key set, no UI)
    fabric.mod.json                              mod metadata
build.gradle                                     Loom build script
gradle.properties                                pinned MC / Fabric / Java versions
```
