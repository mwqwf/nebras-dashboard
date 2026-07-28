package com.nebras.dashboard.core

import java.net.URLDecoder

/**
 * 🧠 استخراج عنوان نظيف من اسم ملف — بلا تدخل بشري.
 *
 * يعالج الأنماط الشائعة في الملفات القادمة من المسجّلات وواتساب ومواقع
 * التنزيل: يزيل الامتداد والترقيم التسلسلي وبصمات المواقع والوسوم
 * الدعائية والفواصل الآلية، ويعيد عنواناً بشرياً مقروءاً.
 *
 * إن كان الاسم آلياً بحتاً لا يحمل معنى (مثل `AUD-20240101-WA0001`)
 * يعيد `""` — فالأصح ترك الحقل فارغاً ليكتبه الإنسان بدل اقتراح خردة.
 *
 * نسخ متطابقة من هذا الملف في مشاريع منبر — عدّلها جميعاً معاً.
 */

private val PATH_SEP = Regex("[/\\\\]")

/** أسماء آلية بحتة — لا شيء ذا معنى يُستخرج منها. */
private val AUTO_PATTERNS: List<Regex> = listOf(
    // واتساب: AUD-20240101-WA0001 وأخواتها + "WhatsApp Audio 2024-01-01 at…"
    Regex("^(AUD|VID|PTT|IMG|DOC|GIF)[-_]\\d{8}[-_]WA\\d+", RegexOption.IGNORE_CASE),
    Regex("^WhatsApp\\s+(Audio|Video|Ptt|Image)\\b", RegexOption.IGNORE_CASE),
    // مسجّلات: Recording 12 / New Recording / record_20240101_123456
    Regex("^(new\\s+)?record(ing)?[-_\\s]*[\\d_.\\-\\s]*$", RegexOption.IGNORE_CASE),
    // أسماء عامة مرقّمة: Voice 003 / Audio 1 / Track05 / ملف 2 / تسجيل 7
    Regex(
        "^(voice|audio|video|sound|note|memo|track|file|item|untitled" +
            "|تسجيل|مقطع|ملف|صوت|بدون\\s*عنوان)[-_\\s]*[\\d٠-٩_.\\-\\s]*$",
        RegexOption.IGNORE_CASE,
    ),
    // أرقام/رموز فقط (طوابع زمنية 20240101_123456 وأمثالها)
    Regex("^[\\d٠-٩\\s_.\\-()~]+$"),
    // أجهزة تسجيل: REC001 / MIC_12 / ZOOM0004 / DS300012
    Regex("^(REC|MIC|ZOOM|DS|DM|VN)[-_]?\\d+$", RegexOption.IGNORE_CASE),
)

/** محتوى دعائي/تقني داخل الأقواس — الأقواس التي تحويه تُحذف كاملة. */
private val JUNK_INSIDE = Regex(
    "(www\\.|https?:|\\.com|\\.net|\\.org|\\.info|kbps|kb/s|\\d{3,4}p\\b" +
        "|mp3|mp4|m4a|wav|flac|\\bhd\\b|\\bhq\\b|\\b4k\\b|official|lyrics" +
        "|audio\\s*only|youtube|download|free|copy|نسخة|تحميل|موقع|بجودة|حصري)",
    RegexOption.IGNORE_CASE,
)

private val BRACKETED = Regex("[\\[({]([^\\])}]*)[\\])}]")

private val SITE_STAMP = Regex(
    "(^|\\s)(www\\.)?[\\w-]+\\.(com|net|org|info|me|tv|cc)(?=\\s|$)",
    RegexOption.IGNORE_CASE,
)

/** ترقيم تسلسلي في البداية: "01 - " / "(12) " / "003." / "٧ ـ " */
private val LEADING_INDEX =
    Regex("^[\\s\\-–—ـ_.]*[(\\[]?\\s*[0-9٠-٩]{1,4}\\s*[)\\]]?[\\s\\-–—ـ_.]+")

private val DECOR_SEPARATORS = Regex("[_~•·]+")
private val INNER_DOT = Regex("(?<=\\S)\\.(?=\\S)")
private val BITRATE_TAG = Regex("\\b(64|96|128|192|256|320)\\s?kbps\\b", RegexOption.IGNORE_CASE)
private val MULTI_SPACE = Regex("\\s+")
private val EDGE_SYMBOLS = Regex("^[\\s\\-–—ـ_.,،؛;:]+|[\\s\\-–—ـ_.,،؛;:]+$")
private val HAS_LETTER = Regex("[A-Za-z؀-ۿ]")
private val UNDERSCORE_OR_DASH = Regex("[_\\-]+")

fun smartTitleFromFileName(fileName: String): String {
    var s = fileName.trim()
    if (s.isEmpty()) return ""

    // اسم الملف فقط (بلا مسار) ثم بلا امتداد.
    s = s.split(PATH_SEP).last()
    val dot = s.lastIndexOf('.')
    if (dot > 0) s = s.substring(0, dot)

    // فكّ ترميز الروابط (%20 → مسافة) إن وُجد. الفكّ يرمي على خليط عربي غير
    // مرمَّز + %20، فنسقط إلى استبدال المسافة المرمّزة فقط.
    if (s.contains('%')) {
        s = try {
            // حماية '+' كي لا يتحوّل إلى مسافة (سلوك Uri.decodeComponent في Dart).
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            s.replace("%20", " ")
        }
    }

    for (p in AUTO_PATTERNS) {
        if (p.containsMatchIn(s)) return ""
    }

    // أقواس بمحتوى دعائي/تقني تُحذف كاملة؛ الأقواس ذات النص العادي تبقى.
    s = BRACKETED.replace(s) { m ->
        val inner = m.groupValues.getOrElse(1) { "" }
        if (JUNK_INSIDE.containsMatchIn(inner)) " " else m.value
    }

    // بصمة موقع طليقة في الاسم: "site.com - العنوان" أو العكس.
    s = SITE_STAMP.replace(s, " ")

    // ترقيم تسلسلي في البداية (مرّتان كحدّ أقصى).
    var i = 0
    while (i < 2 && LEADING_INDEX.containsMatchIn(s)) {
        s = LEADING_INDEX.replaceFirst(s, "")
        i++
    }

    // فواصل آلية إلى مسافات: الشرطة السفلية والنقاط الداخلية والرموز الزخرفية.
    s = DECOR_SEPARATORS.replace(s, " ")
    s = INNER_DOT.replace(s, " ")

    // وسوم جودة علقت وسط الاسم.
    s = BITRATE_TAG.replace(s, " ")

    // اسم بلا مسافات إطلاقاً وفيه شرطات؟ فالشرطات فواصل لا جزء من العنوان.
    if (!s.contains(' ') && s.contains('-')) {
        s = s.replace('-', ' ')
    }

    // تنظيف نهائي: مسافات مكرّرة وحواف رموز.
    s = MULTI_SPACE.replace(s, " ").trim()
    s = EDGE_SYMBOLS.replace(s, "").trim()

    // لا حروف عربية أو لاتينية باقية؟ إذن ليس عنواناً.
    if (!HAS_LETTER.containsMatchIn(s)) return ""
    if (s.length < 2) return ""
    return s
}

/**
 * مثل [smartTitleFromFileName] لكن لا يعيد `""` أبداً — يسقط إلى قصّ
 * الامتداد فقط. للمسارات الجماعية (رفع متعدد) حيث العنوان الفارغ يعطّل.
 */
fun smartTitleOrBasename(fileName: String): String {
    val smart = smartTitleFromFileName(fileName)
    if (smart.isNotEmpty()) return smart
    var s = fileName.split(PATH_SEP).last()
    val dot = s.lastIndexOf('.')
    if (dot > 0) s = s.substring(0, dot)
    return MULTI_SPACE.replace(UNDERSCORE_OR_DASH.replace(s, " "), " ").trim()
}
