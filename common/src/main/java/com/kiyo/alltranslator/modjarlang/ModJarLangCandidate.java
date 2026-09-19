package com.kiyo.alltranslator.modjarlang;

import java.nio.file.Path;

/**
 * One mod jar found to ship an en_us.json lang file but NOT (yet, in its own
 * jar/root paths) a lang file for some target language. Produced by
 * ModJarLangScanner; consumed by ModJarLangTranslationCoordinator, which decides
 * (via GeneratedLangPackStore's hash check) whether translation work is actually
 * still needed for a given target language.
 */
public record ModJarLangCandidate(String modId, Path sourceLangFile) {
}
