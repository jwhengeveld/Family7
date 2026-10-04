package nl.family7.core.data

import java.util.regex.Pattern

/**
 * Leest het stream-adres uit de speler die Family7 op zijn pagina's zet.
 *
 * De speler van Streampartner levert dat adres niet als platte tekst, maar
 * ingepakt in een `eval(function(w,i,s,e){...})`-constructie. Deze code doet
 * precies wat de browser ook doet: de tekst uitpakken en de .m3u8 eruit halen.
 *
 * Apart van de repository gehouden, zodat het gedrag met echte voorbeelden te
 * testen is zonder dat er een netwerkverbinding of een Android-toestel bij komt.
 */
internal object StreampartnerPlayer {

    /** Hoe vaak een speler zijn eigen uitvoer nog eens mag inpakken. */
    private const val MAX_DEPTH = 6

    private val M3U8 = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*")
    private val EVAL_CALL =
        Pattern.compile("eval\\(function\\(w,i,s,e\\)\\{.*?\\}\\((.*?)\\)\\)", Pattern.DOTALL)
    private val JS_STRING = Pattern.compile("'(.*?)'")

    /** Het eerste stream-adres dat letterlijk in de tekst staat, of leeg. */
    fun firstM3u8(text: String): String {
        val matcher = M3U8.matcher(text)
        return if (matcher.find()) matcher.group(0).orEmpty() else ""
    }

    /**
     * Alle stream-adressen in deze tekst, ook die pas na een of meer keer
     * uitpakken zichtbaar worden. De volgorde blijft die van de speler zelf:
     * wat er direct in staat komt eerst.
     */
    fun decodeStreamUrls(text: String, depth: Int = 0): List<String> {
        if (depth > MAX_DEPTH) return emptyList()

        val found = mutableListOf<String>()

        val direct = M3U8.matcher(text)
        while (direct.find()) {
            found.add(direct.group(0).orEmpty())
        }

        val calls = EVAL_CALL.matcher(text)
        while (calls.find()) {
            val arguments = parseJsStringArgs(calls.group(1).orEmpty())
            if (arguments.isEmpty()) continue
            found.addAll(decodeStreamUrls(unpack(arguments), depth + 1))
        }

        return found.filter { it.isNotBlank() }.distinct()
    }

    /** De vier tekenreeksen waarmee de speler wordt aangeroepen. */
    fun parseJsStringArgs(raw: String): List<String> {
        val found = mutableListOf<String>()
        val matcher = JS_STRING.matcher(raw)
        while (matcher.find()) {
            found.add(matcher.group(1).orEmpty())
        }
        return found
    }

    private fun unpack(arguments: List<String>): String {
        val w = arguments.getOrElse(0) { "" }
        val i = arguments.getOrElse(1) { "" }
        val s = arguments.getOrElse(2) { "" }
        val e = arguments.getOrElse(3) { "" }

        // De eenvoudige variant: de speler wordt met vier argumenten aangeroepen
        // maar alleen het eerste is gevuld, en dat is dan kale base36.
        return if (arguments.size == 4 && i.isEmpty() && s.isEmpty() && e.isEmpty()) {
            unpackBase36(w)
        } else {
            unpackInterleaved(w, i, s)
        }
    }

    /** Paren van twee tekens, elk paar een teken in grondtal 36. */
    fun unpackBase36(packed: String): String {
        val out = StringBuilder(packed.length / 2)
        var index = 0
        while (index < packed.length) {
            val pair = packed.substring(index, minOf(index + 2, packed.length))
            // Een onvolledig of ongeldig paar aan het eind slaan we over; de rest
            // van de tekst is dan nog steeds bruikbaar.
            pair.toIntOrNull(36)?.let { out.append(it.toChar()) }
            index += 2
        }
        return out.toString()
    }

    /**
     * De variant met meerdere tekenreeksen. De eerste vijf tekens van elk
     * argument vormen samen de sleutel, de rest de ingepakte tekst; per teken
     * bepaalt de pariteit van het sleutelteken of er een verschoven wordt.
     *
     * Het vierde argument van de speler telt alleen mee in diens eigen
     * lengtecontrole en draagt niets bij aan de uitkomst; het staat hier dus
     * niet in de handtekening.
     */
    fun unpackInterleaved(w: String, i: String, s: String): String {
        val payload = StringBuilder()
        val key = StringBuilder()

        val sources = listOf(w, i, s)
        val longest = sources.maxOf { it.length }
        for (position in 0 until longest) {
            for (source in sources) {
                if (position >= source.length) continue
                if (position < 5) key.append(source[position]) else payload.append(source[position])
            }
        }

        if (key.isEmpty()) return ""

        val out = StringBuilder(payload.length / 2)
        var keyIndex = 0
        var index = 0
        while (index < payload.length) {
            val shift = if (key[keyIndex].code % 2 != 0) 1 else -1
            val pair = payload.substring(index, minOf(index + 2, payload.length))
            pair.toIntOrNull(36)?.let { out.append((it - shift).toChar()) }
            keyIndex = (keyIndex + 1) % key.length
            index += 2
        }
        return out.toString()
    }
}
