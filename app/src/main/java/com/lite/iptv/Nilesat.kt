package com.lite.iptv

/**
 * Free-to-air Arabic channels that are (or were) broadcast on Nilesat 7°W, matched by NAME against whatever
 * playlists are loaded. The app does not receive satellite signals and no stream links are bundled here:
 * channels only appear if one of your playlists has an internet stream for them.
 */
object Nilesat {
    private val keys = listOf(
        // news
        "al jazeera", "aljazeera", "الجزيرة", "al arabiya", "alarabiya", "العربية", "al hadath", "الحدث", "sky news arabia", "سكاي نيوز",
        "al ghad", "الغد", "al mayadeen", "الميادين", "al araby", "العربي", "france 24 arabic", "france 24 arabe", "dw arabia", "dw arabic",
        "bbc arabic", "euronews arabic", "cnbc arabia", "trt arabi", "rt arabic", "rt arabia", "russia al yaum", "alhurra", "الحرة", "al ikhbariya", "الإخبارية", "nile news", "النيل للأخبار", "al qahera news", "القاهرة الإخبارية",
        // MBC / Rotana / entertainment
        "mbc 1", "mbc1", "mbc 2", "mbc 3", "mbc 4", "mbc action", "mbc drama", "mbc max", "mbc masr", "mbc iraq", "mbc persia", "wanasah",
        "rotana", "روتانا", "lbci", "lbc group", "lbc europe", "mtv lebanon", "al jadeed", "الجديد", "otv", "nbn", "al manar", "المنار", "future tv",
        "dubai tv", "sama dubai", "dubai one", "abu dhabi", "emirates", "sharjah", "ajman", "ras al khaimah", "yas tv", "al aan", "al dafrah",
        "kuwait tv", "kuwait", "oman", "bahrain", "qatar tv", "al kass", "الكأس", "saudi", "ksa", "السعودية", "ssc", "al majd", "المجد", "iqraa", "اقرأ", "al resalah", "الرسالة", "wesal", "وصال", "al safa", "صفا", "quran", "القرآن", "sunnah", "السنة",
        // Jordan / Palestine / Iraq / Syria
        "roya", "رؤيا", "al mamlaka", "mamlaka", "المملكة", "jordan", "الأردن", "amman tv", "palestine", "فلسطين", "al sharqiya", "الشرقية", "iraqia", "al iraqiya", "العراقية", "dijlah", "دجلة", "syria", "سوريا", "kurdistan", "rudaw", "kurdsat",
        // Egypt
        "nile tv", "nile sport", "nile drama", "nile cinema", "nile comedy", "nile life", "nile family", "al nahar", "النهار", "cbc egypt", "cbc sofra", "cbc drama", "cbc extra", "cbc plus", "on e", "on tv", "al hayat", "الحياة", "dmc", "ten tv", "sada el balad", "صدى البلد", "mehwar", "المحور", "al kahera wal nas", "القاهرة والناس", "egypt", "مصر", "al ghad el arabi",
        // Maghreb / Sudan / Yemen / Libya
        "2m", "al aoula", "الأولى", "arryadia", "الرياضية", "medi 1", "medi1", "tamazight", "echorouk", "الشروق", "ennahar", "النهار", "canal algerie", "algerie", "al wataniya", "الوطنية", "tunisia", "libya", "ليبيا", "al ahrar", "sudan", "السودان", "yemen", "اليمن", "mauritania", "الموريتانية",
        // kids / music / other
        "spacetoon", "سبيستون", "toyor al janna", "طيور الجنة", "karameesh", "كراميش", "baraem", "براعم", "jeem", "mazzika", "مزيكا", "melody", "ميلودي", "fann", "al hurra"
    )
    private val clean = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    private fun norm(s: String) = " " + clean.replace(s.lowercase(), " ").replace(nonWord, " ").trim() + " "
    private val normKeys = keys.map { " " + it.lowercase().trim() }

    /** True when the channel name starts with / contains one of the known Nilesat channel names as whole words (radio stations are skipped). */
    fun matches(name: String): Boolean {
        val n = norm(name)
        if (n.contains(" radio ") || n.contains(" fm ") || n.contains(" راديو ")) return false
        return normKeys.any { k -> n.contains("$k ") }
    }
}
