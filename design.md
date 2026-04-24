# Voice Command Mod — Full Spec

A Minecraft 1.12.2 Forge mod for accessibility: hold a key, speak, and Claude/Gemini interprets the command and executes it in-game as real inputs. Built for disabled users who want to play survival Minecraft without a controller.

Pipeline is fully in-mod. No Node.js, no MCP server, no sockets. Whisper runs as a subprocess; Gemini is reached over HTTPS.

---

## 1. Build Environment

| Tool | Version |
|---|---|
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2768 |
| Gradle | 4.10.3 (via wrapper) |
| MCP mappings | stable_39 |
| Java | 8 JDK (Azul Zulu 8 for ARM64 Mac) |
| ForgeGradle | 2.3-SNAPSHOT |

No external Java dependencies. Gson ships with Minecraft; audio capture uses `javax.sound.sampled`.

**Runtime dependencies (on the machine running MC):**
- `whisper-cli` (from `brew install whisper-cpp`) at `/opt/homebrew/bin/whisper-cli` or `/usr/local/bin/whisper-cli`
- A GGML model (`ggml-base.en.bin` recommended) in the `.minecraft` folder
- A Gemini API key, either as env var `GEMINI_API_KEY` or as a file `gemini.key` in the `.minecraft` folder

---

## 2. Project Structure

```
build.gradle
settings.gradle
gradle.properties
gradle/wrapper/gradle-wrapper.properties
gradlew, gradlew.bat
src/main/java/com/voiceannounce/
    VoiceAnnounce.java            @Mod entry; wires everything
    PlayerState.java              main-thread snapshot → JSON
    GeminiClient.java             HTTP POST to Gemini, parses functionCall
    ToolSchema.java               builds functionDeclarations JSON
    ConversationHistory.java      rolling messages array (Gemini format)
    ChainValidator.java           step/distance/tool guards
    CommandExecutor.java          dispatches ToolCall → handler
    CommandQueue.java             ticks queued ToolCalls on main thread
    InputState.java               tracks held keybinds + release timers
    ToolCall.java                 POJO: name + args (JsonObject)
    ToolResult.java               POJO: ok/fail + message
    handler/
        KeyInputHandler.java      hold-to-talk keybind (V)
        VoiceRecognitionThread.java   record → whisper → Gemini → queue
        RenderOverlayHandler.java     LISTENING / TRANSCRIBING HUD dot
        GameTickHandler.java          drives CommandQueue + reports results
        CraftingHandler.java      recipe lookup, ingredient check, SP-only
        StationHandler.java       (stub) furnace/anvil/enchanting/brewing
        StorageHandler.java       (stub) deposit/withdraw
        MacroHandler.java         load/save macros, expand run_macro
src/main/resources/
    mcmod.info
    pack.mcmeta
    assets/voiceannounce/lang/en_us.lang
```

---

## 3. Gradle Config

**gradle.properties**
```
mc_version=1.12.2
forge_version=14.23.5.2768
mod_version=1.0.0
org.gradle.daemon=false
org.gradle.jvmargs=-Xmx4G
```

**settings.gradle**
```
rootProject.name = 'voiceannounce'
```

**build.gradle**
```groovy
buildscript {
    repositories {
        maven { url = "https://files.minecraftforge.net/maven" }
        maven { url = "https://repo1.maven.org/maven2/" }
    }
    dependencies {
        classpath 'net.minecraftforge.gradle:ForgeGradle:2.3-SNAPSHOT'
    }
}

apply plugin: 'net.minecraftforge.gradle.forge'

version = "${mc_version}-${mod_version}"
group   = "com.voiceannounce"
archivesBaseName = "voiceannounce"

sourceCompatibility = targetCompatibility = '1.8'

minecraft {
    version    = "${mc_version}-${forge_version}"
    runDir     = "run"
    mappings   = "stable_39"
    makeObfSourceJar = false
}

jar {
    manifest {
        attributes(
            'FMLAT': '',
            'Implementation-Title'  : project.archivesBaseName,
            'Implementation-Version': project.version
        )
    }
}

processResources {
    inputs.property "version", project.version
    inputs.property "mcversion", project.minecraft.version
    from(sourceSets.main.resources.srcDirs) {
        include 'mcmod.info'
        expand 'version': project.version, 'mcversion': project.minecraft.version
    }
    from(sourceSets.main.resources.srcDirs) { exclude 'mcmod.info' }
}
```

Generate the wrapper once with `gradle wrapper --gradle-version=4.10.3`.

---

## 4. Pipeline

```
V pressed → record audio (javax.sound.sampled, 16kHz/16-bit/mono)
V released → whisper-cli → transcript
           → PlayerState.snapshot() on main thread
           → ConversationHistory.appendUser(transcript, state)
           → POST to Gemini (full history + tool declarations)
           → parse functionCall blocks → List<ToolCall>
           → flatten run_macro expansions
           → ChainValidator.validate()
           → CommandQueue.submit()
      tick → CommandExecutor.execute(step) → InputState / handler
           → accumulate ToolResults
  idle tick → GeminiClient.reportResults(results) → appended to history
```

Whisper runs on a background thread (blocking subprocess). Gemini call also runs on that thread (blocking HTTPS). The main thread pulls `PlayerState.snapshot()` synchronously via `mc.addScheduledTask(PlayerState::snapshot).get()` before the HTTP call. CommandQueue ticks on the client tick event (main thread).

---

## 5. Tool Primitives (17)

| Tool | Args | Behavior |
|---|---|---|
| `move` | `direction`, `duration_ms` | Holds WASD for duration. Cardinal dirs rotate yaw first. |
| `look` | `direction` | Sets `rotationYaw` / `rotationPitch` directly. |
| `left_click` | `action` (`press`/`hold`/`release`), `duration_ms` | Attack/mine keybind. |
| `right_click` | same | Use/place/interact. |
| `jump` | — | Taps jump key. |
| `sneak` | `action` (`toggle`/`press`/`release`) | Holds or releases sneak. |
| `sprint` | same | Sprint. |
| `stop` | — | `releaseAll()` on InputState. |
| `select_slot` | `slot` (1–9) | Sets `inventory.currentItem`. |
| `swap_hands` | — | F keybind. |
| `drop` | `whole_stack` (bool) | Q keybind; whole_stack uses `player.dropItem(true)`. |
| `open_inventory` | — | E keybind. |
| `close_container` | — | `player.closeScreen()` on main thread. |
| `craft` | `item`, `quantity` | Direct recipe lookup + inventory mutation. See §9. |
| `deposit` | `item`, `filter` | Stubbed. |
| `withdraw` | `item`, `count` | Stubbed. |
| `respawn` | — | `player.respawnPlayer()` if dead. |
| `run_macro` | `name` | Resolved in `VoiceRecognitionThread` — expansion flattened into the chain before validation. |

Everything maps to real game inputs via `KeyBinding.setKeyBindState(keyCode, state)` or direct inventory/field mutation.

---

## 6. Gemini Integration

**Endpoint:** `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={API_KEY}`

**Request body:**
```json
{
  "systemInstruction": { "parts": [{ "text": "You are a Minecraft..." }] },
  "contents": [
    { "role": "user", "parts": [{ "text": "Player said: \"move forward\"\n\nCurrent state: {...}" }] }
  ],
  "tools": [{ "functionDeclarations": [ /* 17 tool specs */ ] }]
}
```

**Response:** tool calls come back as `candidates[0].content.parts[*].functionCall`, each with `name` and `args`. If Gemini replies with text instead of a tool call (e.g. refusing a goal-seeking command), it arrives in `parts[*].text`.

**Tool results (next turn):**
```json
{ "role": "user", "parts": [
    { "functionResponse": { "name": "move", "response": { "status": "ok", "message": "moved forward for 500ms" } } }
]}
```

**System prompt rules** (embedded in `GeminiClient.systemInstruction()`):
- Sequencer not planner — reject goal-seeking commands in text
- Chain ≤ 5 steps, total movement ≤ 20 blocks
- `forward/back/left/right` relative to facing, `n/s/e/w` absolute
- Pre-check inventory/container from PlayerState before calling craft/deposit/withdraw — reply in text if missing
- Use `run_macro` when the player references a macro by name
- Never emit raw `/commands`

---

## 7. PlayerState Schema

Built on the main thread each voice command:

```json
{
  "position": {"x": 0, "y": 64, "z": 0},
  "facing": 180,
  "pitch": 0,
  "heldItem": "minecraft:diamond_pickaxe",
  "offhandItem": "empty",
  "health": 20,
  "hunger": 18,
  "isDead": false,
  "isInWater": false,
  "isOnGround": true,
  "inventory": {
    "hotbar": [{"slot": 0, "item": "minecraft:cobblestone", "count": 32}, ...],
    "main":   [{"slot": 9, "item": "minecraft:oak_log", "count": 8}, ...]
  },
  "nearbyContainer": {
    "type": "chest",
    "position": {"x": 5, "y": 64, "z": -2},
    "contents": [...],
    "freeSlots": 20
  },
  "macros": [{"name": "mine routine", "description": "look down, break, step forward"}]
}
```

Container scan walks a 10×6×10 box around the player and picks the first `TileEntityChest` within 4.5 blocks.

---

## 8. Conversation History

`ConversationHistory` holds an Anthropic-style (actually Gemini-shaped) messages deque:

```
user:   { text: "Player said: ...\nCurrent state: {...}" }
model:  { functionCall: {...}, functionCall: {...} }
user:   { functionResponse, functionResponse }
user:   { text: "Player said: next command..." }
model:  ...
```

Capped at 30 messages (3 per turn × 10 turns). Oldest drop first. Each turn's fresh `PlayerState` goes into the current user text — old state never lingers.

`GeminiClient.reportResults()` appends a `user` role message with `functionResponse` parts, one per executed step. Gemini sees the outcome (including failures like `"missing ingredients for minecraft:stone_pickaxe"` or `"need a crafting table within 4.5 blocks"`) on the next turn and adjusts.

---

## 9. Crafting

Implemented against Forge APIs, **singleplayer only** (client-side mods cannot mutate server inventory in multiplayer without a server-side counterpart).

**Recipe lookup:** iterate `CraftingManager.REGISTRY`, return the first recipe whose `getRecipeOutput().getItem()` matches the requested `Item` (resolved via `Item.REGISTRY.getObject(new ResourceLocation(...))`).

**2×2 vs 3×3:**
- `recipe.canFit(2, 2) == true` → 2×2 recipe (planks, sticks, torches, crafting table, etc.). Crafts from inventory directly, no table required — same as vanilla player grid.
- `recipe.canFit(2, 2) == false` → 3×3 recipe (tools, armor, doors, chests, pickaxes, etc.). Scans a 10×6×10 box for `Blocks.CRAFTING_TABLE` within 4.5 blocks; fails with `"need a crafting table within 4.5 blocks"` if none found.

**Ingredient matching:** `Ingredient.apply(ItemStack)` respects the ore dictionary (any plank type satisfies "planks", any wood log satisfies "logs", etc.). `planConsumption()` builds a `Map<slot, count>` across the full 36-slot main inventory, tracking partial consumption so one stack of 64 planks can cover a recipe needing 4 planks.

**Execution:** the craft runs on the integrated server thread via `server.addScheduledTask(...)` to mutate the authoritative `EntityPlayerMP.inventory`. After `decrStackSize()` + `addItemStackToInventory()` we call `container.detectAndSendChanges()` to sync the client. Overflow drops at the player's feet via `player.dropItem(out, false)`.

**Quantity:** `crafts = ceil(quantity / recipe.getRecipeOutput().getCount())`. Stops early on partial ingredient availability and reports the partial count.

---

## 10. Chain Validation

`ChainValidator.validate(chain)`:
- Step count ≤ 5
- Per-step `duration_ms` ≤ 5000
- Total movement ≤ 5000ms (~20 blocks at 1 block / 250ms)
- Tool name must be in the allowed set (17 primitives + `run_macro`)

Failure returns a string; `VoiceRecognitionThread` chats it to the player and appends a `"_validator"` functionResponse to history so Gemini sees the rejection.

---

## 11. Input Simulation

`InputState` wraps `KeyBinding.setKeyBindState(keyCode, pressed)` with auto-release timers:

| Method | Effect |
|---|---|
| `hold(kb, durationMs)` | Sets pressed=true, schedules release. `durationMs=0` means hold indefinitely. |
| `press(kb)` | Pressed=true + `KeyBinding.onTick()` to register a click; scheduled release ~60ms later. |
| `release(kb)` | Pressed=false immediately. |
| `tick()` | Called every client tick; releases expired held keys. |
| `releaseAll()` | Zeros everything. |

Well-known bindings exposed as static helpers (`forward()`, `attack()`, `useItem()`, etc.) pulled from `mc.gameSettings.keyBind*`.

**Camera:** `look` writes `player.rotationYaw` and `rotationPitch` directly — no mouse simulation needed.

---

## 12. Macros

Stored in `.minecraft/config/voicecommand_macros.json`:

```json
[
  {
    "name": "mine routine",
    "description": "Look down and break the block under you, then step forward.",
    "steps": [
      { "tool": "look",       "args": { "direction": "down" } },
      { "tool": "left_click", "args": { "action": "hold", "duration_ms": 1500 } },
      { "tool": "move",       "args": { "direction": "forward", "duration_ms": 300 } }
    ]
  }
]
```

Auto-created on first launch with a default "mine routine". Macro `{name, description}` pairs are injected into every PlayerState so Gemini sees available names. When Gemini calls `run_macro({name})`, `VoiceRecognitionThread` catches it pre-validation, calls `MacroHandler.runMacro()` to populate an expansion list, and splices those steps into the chain before validation runs. Macro steps count toward the 5-step and 20-block limits like any other.

---

## 13. Entry Point (VoiceAnnounce.java)

**postInit:**
1. Find `whisper-cli` — probe `/opt/homebrew/bin/whisper-cli`, `/usr/local/bin/whisper-cli`, `/usr/bin/whisper-cli`, fall through to `which whisper-cli`.
2. Find GGML model — prefer `ggml-base.en.bin`, then small/medium/tiny/large, then any `ggml*.bin` in `.minecraft`.
3. `MacroHandler.loadFromGameDir(gameDir)` — creates default file if absent.
4. Resolve Gemini key — env `GEMINI_API_KEY` first, then `.minecraft/gemini.key`.
5. Construct `GeminiClient` if key present.

If any dependency is missing, the mod logs an error and the keybind is a no-op.

**Registered event handlers:**
- `KeyInputHandler` — V keybind state machine
- `RenderOverlayHandler` — HUD dots
- `GameTickHandler(commandQueue)` — drives queue tick + reports results on idle transition

---

## 14. Installation / Setup

**Build:**
```sh
export JAVA_HOME="/path/to/zulu-8.jdk/Contents/Home"
./gradlew build
# output: build/libs/voiceannounce-1.12.2-1.0.0.jar
```

**Prism Launcher instance** (creating one called `Claude2026Test`):

1. Create directory: `~/Library/Application Support/PrismLauncher/instances/Claude2026Test/minecraft/mods/`
2. Write `instance.cfg`:
   ```ini
   [General]
   InstanceType=OneSix
   name=Claude2026Test
   JavaPath=/path/to/zulu-8.jdk/Contents/Home/bin/java
   OverrideJavaLocation=true
   OverrideMemory=true
   MaxMemAlloc=4096
   MinMemAlloc=2048
   PermGen=128
   ```
3. Write `mmc-pack.json`:
   ```json
   {
     "components": [
       { "uid": "org.lwjgl", "version": "2.9.4-nightly-20150209", "dependencyOnly": true },
       { "uid": "net.minecraft", "version": "1.12.2", "important": true },
       { "uid": "net.minecraftforge", "version": "14.23.5.2860" }
     ],
     "formatVersion": 1
   }
   ```
4. Copy JAR into `.../minecraft/mods/`.
5. Install runtime deps:
   ```sh
   brew install whisper-cpp
   curl -L https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin \
     -o ~/Library/Application\ Support/PrismLauncher/instances/Claude2026Test/minecraft/ggml-base.en.bin
   echo "YOUR_GEMINI_KEY" > ~/Library/Application\ Support/PrismLauncher/instances/Claude2026Test/minecraft/gemini.key
   ```

**Hot reload** — `hot-reload.sh` polls `src/` every 2 seconds, rebuilds with Gradle, copies the JAR to the instance mods folder. Forge can't hot-swap loaded classes; you restart MC to pick up changes.

---

## 15. macOS Microphone Permission

Grant mic access to the launcher (Prism Launcher.app or the official launcher). If the prompt never appeared: `tccutil reset Microphone`, then relaunch.

---

## 16. Resource Files

**src/main/resources/mcmod.info:**
```json
[{
  "modid": "voiceannounce",
  "name": "Voice Command",
  "description": "Voice-driven survival actions via Whisper + Gemini.",
  "version": "${version}",
  "mcversion": "${mcversion}",
  "authorList": [],
  "dependencies": []
}]
```

**src/main/resources/pack.mcmeta:**
```json
{"pack": {"description": "Voice Command resources", "pack_format": 3}}
```

**src/main/resources/assets/voiceannounce/lang/en_us.lang:**
```
key.voiceannounce.speak=Voice Command: Speak
key.categories.voiceannounce=Voice Command
```

---

## 17. Multiplayer / Future Work

- Multiplayer crafting, deposit, withdraw require a server-side counterpart mod (can't mutate server inventory client-side).
- `deposit` / `withdraw` / furnace / enchanting / anvil / brewing currently stubbed — return `"not yet implemented"` which Gemini sees and relays.
- Whisper timeouts at 30s. Long utterances (>15s speech) may need a larger model (`ggml-small.en.bin` or bigger).
- History truncation is naive (drop-oldest). For long sessions a summarization pass would be more context-efficient.

Still to implement
- ability to hold v in menus (crafting table, etc)