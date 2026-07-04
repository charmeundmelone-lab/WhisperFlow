package de.minitraxx.whisperflow.api

/**
 * Gemeinsamer Whisper-Kontext-Prompt für BEIDE Transkriptionspfade
 * (Cloud: WhisperClient, lokal: LocalWhisperEngine) — einzige Quelle,
 * damit die Pfade nie auseinanderlaufen.
 *
 * Whisper behandelt den Prompt wie vorangegangenen Transkript-Text und
 * setzt dessen Stil fort. Ein bewusst umgangssprachlicher Beispieltext
 * bringt das Modell dazu, Alltagssprache ("halt", "ne", Verkürzungen wie
 * "sag's" / "'ne") als erwartete Wörter zu erkennen, statt sie zu
 * "korrigieren" oder zu verwerfen — und liefert nebenbei das Muster für
 * natürliche Satzzeichen. Der Prompt wird NICHT mit ausgegeben, er ist
 * reiner Kontext.
 */
object WhisperPrompts {

    /** Umgangssprachliches Deutsch — Standard (auch für Auto-Erkennung und Plattdeutsch, language="de"). */
    private const val GERMAN =
        "Also, ich sag's dir mal ganz ehrlich: Das war gestern echt so 'ne Sache, ne? " +
        "Wir haben halt kurz gequatscht, und er meinte nur, ja gut, passt schon, " +
        "machen wir einfach so. Und ich so: Na klar, wieso auch nicht?"

    /** Umgangssprachliches Englisch — nur wenn im Radialmenü explizit EN gewählt ist. */
    private const val ENGLISH =
        "So yeah, I was just gonna say — we kinda talked it over real quick, and he " +
        "was like, yeah, that's fine, let's just do it that way. And I said, sure, why not?"

    /**
     * Passender Kontext-Prompt zur Whisper-Sprache.
     * [language] wie überall: ""/blank = Auto-Erkennung, sonst ISO-Code ("de", "en").
     */
    fun contextPrompt(language: String): String =
        if (language.trim().equals("en", ignoreCase = true)) ENGLISH else GERMAN
}
