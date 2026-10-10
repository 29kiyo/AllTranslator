# All Translator

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/alltranslator?style=for-the-badge&logo=modrinth&label=Modrinth)](https://modrinth.com/mod/alltranslator) [![CurseForge Downloads](https://img.shields.io/curseforge/dt/1736070?style=for-the-badge&logo=curseforge&label=CurseForge)](https://www.curseforge.com/minecraft/mc-mods/alltranslator) [![GitHub Issues](https://img.shields.io/github/issues/29kiyo/AllTranslator?style=for-the-badge&logo=github&label=Issues)](https://github.com/29kiyo/AllTranslator/issues)


A translation mod for Minecraft (NeoForge / Fabric, MC 26.3).
It translates in-game text — item names, tooltips, entity names, chat, and more —
using external translation APIs that you configure yourself.

The mod itself does not provide a translation API. Register the API you want to use
from the settings screen: OpenAI-compatible APIs (such as ChatGPT), local LLMs
(LM Studio, Ollama, etc.), Anthropic, Gemini, DeepL, Google Cloud Translation v2,
the free Google Translate web version, and so on.

## Main Features

- **Existing translations take priority**: If a valid translation already exists in the
  target language's Minecraft/mod language files, or in
  `config/alltranslator/lang/<language code>.json`, it is used as-is without calling any API.
  Boss bar names (both vanilla and modded, as long as the Component retains a translation key)
  also prefer existing translations.
- **Translation targets**: Item names, tooltips, entity names (player names are excluded),
  player chat, `/tellraw`, `/msg` · `/tell` · `/w` · `/teammsg`, `/title`,
  advancement/recipe toast notifications, advancement chat announcements, boss bar names,
  the active effects list, and other mods' config screens (vanilla components, libIPN, UI Lib).
  There is no dedicated hook for block names (see "Known Limitations" for details).
- **Per-item ON/OFF for translation targets**: Each item can be toggled from "Translation Targets"
  in the settings screen. All are off by default to prevent unintended heavy API consumption
  (only the items you turn on are translated).
- **Multiple API settings + automatic failover**: You can register multiple APIs with priorities,
  and the mod automatically switches to the next API on rate limits, quota exhaustion,
  authentication failures, and so on. It automatically attempts to recover after a certain time.
  Empty responses, translations missing placeholders (such as `%s`), and abnormally shaped
  responses returned with HTTP 200 are also treated as failures and passed on to the next API.
- **API selection mode**: In the settings screen (the server settings screen as well), you can switch
  between "Priority order" (default) and "Distributed". "Distributed" uses the API with the fewest
  in-flight requests first, spreading the workload across multiple APIs. This has been verified
  with two kinds of local LLMs (LM Studio + Ollama).
  On a server, OPs can change it from the settings screen, and it takes effect without a restart.
- **Supported providers**: OpenAI-compatible (for ChatGPT), LM Studio, Ollama,
  Anthropic (Claude), Gemini, DeepL, Google Cloud Translation v2,
  Google Translate web version (free, unofficial, chat only), generic REST, via server (proxy).
- **Server support**: Server-side per-player translation, per-player language settings,
  a server translation gateway (clients translate using the server's API without holding API keys),
  and a settings screen where OPs can edit server settings in-game.
- **Bulk mod jar translation**: Detects mods whose bundled English language files lack a
  target-language version, or have only some keys untranslated, then translates them in bulk
  after confirmation and applies the result as an always-enabled resource pack.
- **Persistent cache**: Translation results (excluding chat) are saved inside the world save
  to minimize API calls.
- **API status scoreboard display**: Shows the number of in-flight requests, status, and
  queued items for each enabled API in the sidebar (off by default).
- **Concurrent request adjustment**: Can be changed from the settings screen (intended for local LLMs).
- **JEI integration** (verified on real hardware for both Fabric and NeoForge): In JEI search,
  items can be found by either their translated name or their original (English) name.
  It only works when `Enable translation` and `Item names` translation are on.
  See "JEI integration" under "Known Limitations" for details.

## Installation

1. Install NeoForge or Fabric (a supported version of the mod loader)
2. Place `alltranslator-fabric-<version>.jar` or `alltranslator-neoforge-<version>.jar`
   in the `mods` folder
3. After launching the game, press the `M` key (changeable in the key bindings) to open the
   settings screen and register at least one translation API

The settings screen can be opened in any of the following ways (same screen, same settings):
the `M` key, Mod Menu (Fabric), or the settings button on NeoForge's "Mods" screen
(all verified on real hardware).

## Basic Usage

1. Press `M` to open the settings screen and add a translation API under "API"
   (provider, endpoint, API key)
2. Under "Translation Targets", turn on the items you want translated (all are off by default.
   The translation API is only called for the items you turn on)
3. The target language defaults to Minecraft's display language. You can change it in the
   target language field of the settings screen (leaving it blank follows the display language)
4. If you use a local LLM, check "Notes on Using Local LLMs" below

## Keys and Commands

| Action | Description |
|---|---|
| `M` key | Open the settings screen |
| `K` key | Run `/alltranslator refresh` |

The command is `/alltranslator` (short form `/at`).

| Command | Description | Permission |
|---|---|---|
| `enable` / `disable` | Turn your own translation on/off | Anyone for themselves (OP for other players) |
| `status` | Show the current status | Anyone |
| `language <code>` | Use a different language for yourself only | Anyone for themselves (OP for other players) |
| `refresh` | Clears API cooldowns, etc., and forcibly aborts HTTP requests already sent (permanently disabled APIs are not affected) | OP, or the world owner in singleplayer |
| `config` | Opens the server's settings screen (API keys are not sent over the network) | OP |

When a non-OP tries to run `config`, no suggestions appear in tab completion, and the command
is treated as if it does not exist (verified on both Fabric and NeoForge).

## Using on a Server

- It can be installed on both the server and the client.
- **Server-side per-player translation**: When the server administrator enables
  `serverSideChatTranslationEnabled`, the server translates for each recipient and delivers the
  result (the sender is not translated for. When only one player is online, it also translates
  for that player). Showing the original text alongside follows the server administrator's settings.
  The client is automatically notified that the server has it enabled, so messages are not
  translated twice even if you haven't changed the client's local settings (verified on
  Fabric/NeoForge).
- **How each player's language is determined** (highest priority first):
  1. The language chosen with `/alltranslator language`
  2. The target language in the `M` key settings screen (automatically synced to the server on
     login and on save; only the language code is sent)
  3. The server's `forcedTargetLanguage` (the server default when an individual hasn't chosen one)
  4. Minecraft's display language (automatically synced to the server)
  5. `en_us`
- **Handling of API keys**: Client API keys are never sent to the server.
  Server-side translation uses the APIs that the server administrator has configured on their own server.
- **Server translation gateway** (provider "Via server (proxy)"): When a client registers this API,
  it sends only the source text and language code to the server and receives the result translated
  with the server's own API. It only works when the server administrator has enabled
  `serverProxyTranslationEnabled` (off by default). When it is off, the request immediately
  counts as a failure and is retried at roughly 30-second intervals (verified on real hardware).
  The response wait limit defaults to 150 seconds (`serverProxyTimeoutSeconds`).
  Even on timeout, the game does not freeze and continues to run normally (verified on real hardware).
- **Server settings screen**: OPs open it with `/alltranslator config`, then edit and save settings
  (there is no API key field. APIs added in this screen have no credentials attached).
  If the window is too small, only a warning is displayed.

## Bulk Mod Jar Translation

1. When the title screen is first shown (once per session for each target language), if there are
   mods that lack a target-language language file or have only some keys untranslated,
   a confirmation screen opens.
2. Check the mods you want to translate and run it; the mods are translated one at a time.
3. The results are written to `config/alltranslator/generated_lang_packs/` as a single resource pack,
   "All Translator: generated translations", which is always enabled.
4. After completion (or via the "Apply" button on the screen), resources are reloaded and the text
   is displayed in Japanese or your target language.

- You can also open it manually from "Translate mod language files..." in the settings screen.
  The "Don't show again" option on the confirmation screen stops the automatic display.
- Mods are not re-translated unless the contents of the original language file change. The generated
  `<language code>.json` can be edited by hand.
- This is a fully client-side feature (no server installation is needed).

## Configuration Files

| File | Contents |
|---|---|
| `config/alltranslator/config.json` | General settings and API settings (does not contain the keys themselves) |
| `config/alltranslator/credentials.json` | The API keys themselves (do not put under Git or share) |
| `config/alltranslator/lang/<code>.json` | User-made translation files (top priority as existing translations) |
| `config/alltranslator/player-settings.json` | Server-side per-player translation settings (server-local) |
| `config/alltranslator/generated_lang_packs/` | Packs generated by bulk mod jar translation |
| `<world>/alltranslator/translations/` | Persistent cache of translation results (inside the world save) |

Setting `debugLoggingEnabled` in `config.json` (default false) to `true` outputs detailed logs of
translation requests (`[AT-DEBUG]`). There is no toggle in the settings screen, so edit `config.json`
directly (use this when investigating or reporting problems).

## Notes on Using Local LLMs

- **Timeout**: The HTTP timeout for OpenAI-compatible APIs defaults to 30 seconds. Small models in the
  7B class may take more than 30 seconds for long text (tooltip descriptions or long chat messages);
  in that case the request fails with a timeout and the API temporarily enters a cooldown state.
  Set a longer value in the API settings' `extraParams`, such as `"timeoutSeconds": "120"`.
- **Concurrent requests**: In large modpacks, opening the inventory queues up a large number of requests
  at once, which can take several seconds to tens of seconds on underpowered machines. Lowering the
  concurrent request count in the settings screen reduces the load on the local LLM. If things get stuck,
  use `/alltranslator refresh` (`K` key) to abort requests that have already been sent.
- **Running multiple local models at the same time (distributed mode)**: Running two 7B–8B-class models
  simultaneously can be heavy for a single PC (verified on real hardware). When using distributed mode,
  consider your machine's specs.
- **Translation quality**: Quantized 7B-class models may probabilistically mistranslate proper nouns and
  technical terms (language names, song titles, mod terminology, etc.) (e.g., "Albanian" → "アラビア語"
  [Arabic]). This is a limitation of the model, not a bug in the mod. Adding
  `{"source text": "correct translation"}` to `config/alltranslator/lang/<language code>.json` makes it
  used as an existing translation with top priority, so override mistranslations in this file.

## Known Limitations

### Scope of Translation Targets

- **There is no dedicated hook for block names**: Block names in the inventory, hotbar, and tooltips are
  covered by the item name translation path as block items (`BlockItem`), but some paths that call
  `Block#getName()` directly (such as the Structure Block / Jigsaw Block edit screens) are not covered.

### Providers

- **OpenAI-compatible (for ChatGPT) / LM Studio**: Verified on real hardware. LM Studio (formerly "Local")
  does not send an `Authorization` header when the API key is omitted. `llama.cpp` (llama-server) is said
  to provide the same protocol, but is untested. Settings from the former "Local" can be used as-is
  (only the display name changes to "LM Studio").
- **Ollama**: Implemented using an OpenAI-compatible endpoint (the default is
  `http://localhost:11434/v1/chat/completions`) and verified on real hardware (translation succeeded
  after registering a GGUF model with `ollama create`, and actual request distribution in distributed
  mode was also confirmed). We have not been able to confirm that Ollama supports the "Fetch models"
  button (`/v1/models`). If it doesn't work, enter the model name directly.
- **Google Translate web version (free, unofficial)**: Verified on real hardware, but because it is a free,
  unofficial, uncontracted endpoint, rate limiting (HTTP 429) may be applied without notice, for reasons
  such as IP-based limits. This is a constraint of the external endpoint, not a bug in the mod. It may also
  stop working due to specification changes on Google's side. If it is rate-limited frequently, place
  other providers at higher priority.
  For this reason it is **chat only** (player chat and `/msg` · `/tell` · `/w` · `/teammsg`).
  It is not used for item names, tooltips, `/tellraw`, `/title`, toasts, boss bar names, bulk mod jar
  translation, and so on. If this is the only enabled API, everything other than chat remains in the
  original text. Chat that goes through the server translation gateway (via server) is treated as
  "non-chat" on the server side, so the server's free Google is not used.
- **Anthropic (Claude) / Gemini / DeepL / Google Cloud Translation v2**: Implemented, but not yet verified
  with real API keys (for DeepL and Google Cloud, only the response shape was verified against mock
  servers based on the official documentation). Please test with a small amount of text before use.
- **FTB Quests integration**: Not implemented, because no official release exists for this mod's target
  Minecraft version at present (even if it is installed, it is only detected and does not crash).

### Other Mods' Screens

- **libIPN** (used by Inventory Profiles Next, etc.): Supported and verified on real hardware.
- **UI Lib**: Supported, but the actual translation behavior (text being rewritten) has not been verified
  on real hardware (only detection has been confirmed).
- **MaLiLib, Sodium, Cloth Config, YACL, owo-lib**: Support has been postponed because there is no public
  API for reading/writing displayed text or enumerating child elements (our policy is not to use
  `setAccessible` on non-public fields). We will reconsider if public APIs are added in the future.
- **Iceberg**: It has no Widget/text system of its own, so no support is needed.

### Chat and Messages

- **Scope of translation**: Player chat, `/msg` · `/tell` · `/w` · `/teammsg`, `/tellraw`, `/title`, and
  advancement announcements. Command feedback and other system messages generated by mods or data packs
  are not covered.
- **`/title`**: It is sent within the command's synchronous processing, so it cannot wait for translation
  to complete. The same text is translated from the second time it is run (the first time shows the
  original text and starts translating in the background).
- **Sending the same text 3 or more times in a row**: This has been greatly improved by the retry queue
  approach, but if the same text is sent 3 or more times in a short period and the completion order of
  translations is greatly out of sequence, the correspondence may occasionally be misaligned.
- With server-side translation, the original-text display (`showOriginalTextInChat`) follows the server
  administrator's setting for all recipients. Individual clients' settings are not reflected.

### Other

- **API selection mode "Distributed"**: Verified with real APIs (LM Studio + Ollama, two kinds of local
  LLMs). The detection of abnormal responses is conservative: empty responses, missing placeholders, and
  unexpected response shapes under HTTP 200 (abnormal length is not checked). Small local models may drop
  tokens in text containing placeholders (such as chat). In that case it counts as a failure and is passed
  on to the next API, and if all fail, the original text remains.
- **JEI integration**: Uses JEI's alias feature so that, in JEI search, items can be found by either their
  translated name or their original (English) name.
  - Behavior: In an environment with an English game and Japanese as the target language, we confirmed that
    searching for `ダイヤモンドの剣` (Diamond Sword) finds the Diamond Sword, on both Fabric and NeoForge.
  - The aliases are the original (English) name and the target-language name (the existing translation from
    the language files, or the saved translation cache if there is none). No API is called.
  - Aliases are registered when JEI starts (and when JEI restarts due to a resource reload). Names whose
    translation finishes after that are not reflected in search until JEI next restarts.
  - JEI also shows the aliases in item tooltips ("Search Aliases:"). There is not yet a setting that hides
    only this display. If you want to hide it, turn off JEI's "Search Extra Ingredient Names" setting
    (this also disables searching by alias).
  - We confirmed that alias search works in every combination: an English name (e.g., `diamond sword`) in a
    Japanese game, and a Japanese name (e.g., `ダイヤモンドの剣`) in an English game (both Fabric and
    NeoForge).
  - **NeoForge**: Verified on real hardware (JEI 30.35.0.223; both alias search and tooltip display work).
  - **REI**: **Not supported (will not be implemented)**. We confirmed that REI has no official API for
    adding aliases (we fully unpacked the jar and confirmed there is no reference whatsoever to JEI's
    plugin mechanism).
  - Tooltips in JEI's own item list (JEI's grid, not the inventory screen) show only existing translations,
    and no translation API requests are made even for untranslated items (because JEI internally builds its
    own tooltips).
- **Boss bar names**: Verified with the vanilla Wither (case with an existing translation) and the Ender
  Dragon (including after the fix to prefer existing translations). For bosses that retain an existing
  translation key, the vanilla/mod's own ja_jp translation is used with priority (no API is called). Modded
  bosses whose names are set as literal strings without a key are sent to the translation API as key-less
  dynamic text (not yet verified on real hardware with a modded boss).
- **Clients joining via LAN (not the host)**: Since they do not start a server themselves, translation
  results are not saved to the persistent cache inside the world save (memory cache only).
- **Colored text**: Some mods (confirmed: Traveler's Backpack) embed colors directly in the text as color
  codes such as `§6`. All Translator splits lines of this kind at color changes, translates each fragment,
  and mechanically reassembles the color codes without passing them to the translation API. As a result, the
  context of the whole original sentence is lost, so the translation may become somewhat unnatural or the
  range a color applies to may shift (breakage such as colors disappearing or unrelated lines being colored
  does not occur).
- **Bulk mod jar translation**:
  - Translation quality depends on the model used. The generated language files can be edited by hand.
  - For mods that have even part of a target-language file in the same jar, only untranslated keys (those
    whose value is identical to en_us) are detected and translated to fill the gaps.
  - Only mods whose language files are in `assets/<modId>/lang/` are covered. Mods whose namespace differs
    from the mod ID are not detected.
  - Language files provided by other resource packs are not taken into account.
  - Progress is displayed per mod (a mod with many keys may stay at "0 / 1" without moving for a while).
  - Libraries (e.g., ApolLib bundled in Lithostitched) also appear as candidates (you can uncheck them).
- **NeoForge**: The main features (the permanent fix for chat double translation, the server translation
  gateway, non-OP rejection check, abnormal response classification, dedicated server startup, settings
  screen, tooltips, boss bars (including existing-translation priority), bulk mod jar translation,
  `/tellraw`, `/alltranslator config`, the Mod Menu alternative route, config.json ordering, and debug log
  setting) have been verified on real hardware, as with Fabric. Some features have only been verified on
  Fabric.

## Development

Architectury (common/fabric/neoforge) structure. Build as follows:

```bash
./gradlew build
```

For development runs, use `fabric/run-server` for the Fabric dedicated server and
`neoforge/run-server` for the NeoForge dedicated server (they are separated so as not to mix with the
client's settings).

GitHub Actions (`.github/workflows/build.yml`) provides tag-specified releases via `workflow_dispatch`
(tag format validation, build, tests, artifact verification, GitHub Release creation, and upload).
It is not triggered by ordinary `push` events.

## License

[GNU Lesser General Public License v3.0](LICENSE)
