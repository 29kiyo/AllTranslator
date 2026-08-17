package com.kiyo.alltranslator.compat.ftbquests;

import dev.architectury.platform.Platform;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Phase 7 (partial): FTB Quests optional compatibility - detection-only skeleton.
 *
 * CLAUDE.md §3 (source-first rule) / §21 (no invented APIs): as of this Phase, no FTB
 * Quests build compatible with Minecraft 26.2 could be found (checked FTB Quests' own
 * GitHub gradle.properties and CurseForge listings; both top out at MC 1.21.1 at the
 * time of writing). A compile-time dependency on a non-existent 26.2 release isn't
 * possible, and guessing at hook signatures from the 1.21.x API (quest
 * title/description/objective/reward text) would repeat the exact mistake Phase 6 hit
 * with ServerPlayer#getLanguage() - implementing against an API that isn't actually
 * there for this version.
 *
 * What IS implemented here: presence detection only, via Architectury's own
 * Platform.isModLoaded(String) (dev.architectury.platform.Platform - already a project
 * dependency since Phase 1, verified against architectury-21.0.7-sources.jar in Phase
 * 4/7; no new dependency needed). No FTB Quests classes are referenced anywhere in this
 * file, so this compiles and runs safely whether or not FTB Quests (in any version) is
 * present (CLAUDE.md §16).
 *
 * When a MC 26.2-compatible FTB Quests release exists: add it as a compileOnly
 * dependency (ARCHITECTURE.md §14), verify its actual quest-text hook API via its
 * sources jar the same way this project verified Mod Menu's API in this same Phase,
 * then implement hooks here, wired through
 * LocalizedTextResolver.resolve(key=null, sourceText) (ARCHITECTURE.md §3 - FTB Quests
 * text is keyless dynamic content, world-save persistent-cache eligible) with
 * PlaceholderProtector around any raw text that might contain placeholders/formatting
 * codes.
 */
public final class FtbQuestsCompat {

    private static final String FTB_QUESTS_MOD_ID = "ftbquests";

    private FtbQuestsCompat() {}

    public static boolean isPresent() {
        return Platform.isModLoaded(FTB_QUESTS_MOD_ID);
    }

    /** Called once during common init (both physical sides). Logs status only; installs no hooks. */
    public static void reportStatus() {
        if (isPresent()) {
            AllTranslator.LOGGER.info(
                    "FTB Quests detected, but All Translator has no MC 26.2-compatible FTB Quests "
                            + "integration yet (no such FTB Quests release exists as of this build). "
                            + "FTB Quests text will not be translated until a future update.");
        } else {
            AllTranslator.LOGGER.info("FTB Quests not detected; skipping FTB Quests integration (optional).");
        }
    }
}
