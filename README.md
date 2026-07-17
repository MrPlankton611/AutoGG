# AutoGG

A small **Fabric** mod for **Minecraft 1.21.11** that watches the chat HUD on
multiplayer servers. When a chat line contains any of a hardcoded set of
trigger substrings (trophy `🏆`, `Winner(s):`, `Match Report`, etc.), the
mod sends `gg` once per cooldown window and stops.

That's the whole mod. There is no config file, no in-game menu, no keybind,
and no settings UI — it only cares about the chat log.

- Mod id: `autogg`
- Version: `1.0.0`
- License: GPL-3.0
- Java: 21
- Fabric Loader: `>= 0.16.9`
- Fabric API: `0.141.5+1.21.11`
- Yarn mappings: `1.21.11+build.1`

## Install (player)

1. Build (see *Build from source*) or grab `build/libs/autogg-1.0.0.jar`.
2. Copy it into your Minecraft `mods/` folder
   (Windows: `%AppData%\.minecraft\mods\`).
3. Launch Minecraft 1.21.11 with Fabric Loader 0.19.3 (or newer 0.16.9+) and
   Fabric API installed.
4. That's it — the mod is active on any multiplayer server.

The mod only acts on multiplayer servers. Singleplayer is excluded by design.

## What it watches for

A new chat line is matched (case-insensitive substring) against:

```
🏆
Winner(s):
First to:
Match Report
Match Log
Game Log
Match Summary
Game Over!
```

If any trigger matches, the mod sends `gg` through the player's network
handler and waits 5 seconds before being willing to send again (so a
multi-line match summary doesn't fire `gg` five times in a row).

To change the triggers or the response message, edit the `TRIGGERS` and
`RESPONSE` constants in `AutoGGMod.java` and rebuild.

## How the chat HUD is read

`AutoGGMod.findMessagesList` walks `ChatHud.getClass().getDeclaredFields()` on
every tick and picks the first non-static `List<?>` whose first non-null
element is **not** a `java.lang.String`. The `String`-filter is essential:
vanilla 1.21.11 `ChatHud` carries a typed-message history list
(used for up-arrow recall of past `/msg` and commands) whose elements are
`String`. Without the filter the reflection deterministically picks that
list — `MATCH REPORT` lines never appear in it, so the mod would never fire.

The mod keeps an identity-based set of `ChatHudLine` references it has
already processed so that previously-seen lines aren't re-scanned and
re-fired every tick. The set is trimmed every tick to the currently
visible chat window so it doesn't grow without bound over a long session.

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

## Troubleshooting

- **Nothing fires on a clear match-end line** — the trigger list is hardcoded
  in `AutoGGMod.java`. If the server's match message uses a phrase that
  isn't in the list, no match will fire. Edit the source and rebuild.
- **Triggers fire twice** — they shouldn't. There's a 5-second cooldown, and
  the server echo of `gg` is dropped by the self-echo guard.
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
