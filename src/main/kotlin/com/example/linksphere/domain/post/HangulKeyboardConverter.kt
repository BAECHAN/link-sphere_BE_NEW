package com.example.linksphere.domain.post

/**
 * 한/영 자판 배열 미스매칭 변환기 (2벌식 표준 자판 기준).
 *
 * 검색어가 의도한 자판과 다른 상태로 입력됐을 때(예: spdlqj -> 네이버, 메ㅔㅣㄷ -> apple)
 * 보정하기 위한 유틸. PostService/BookmarkFolderService에서 검색 결과 0건일 때만
 * 폴백으로 호출한다 — 항상 변환하면 정상적인 영단어(예: java)까지 깨뜨릴 수 있다.
 */
object HangulKeyboardConverter {

    // 2벌식 표준 자판: 영문 키 -> 자모. Shift 조합(쌍자음/쌍모음)은 Q W E R T O P에만 존재한다.
    private val EN_TO_JAMO: Map<Char, Char> = mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
        'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
        'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
        'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ',
        'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ', 'b' to 'ㅠ', 'n' to 'ㅜ', 'm' to 'ㅡ',
        'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ', 'O' to 'ㅒ', 'P' to 'ㅖ',
    )
    private val JAMO_TO_EN: Map<Char, Char> = EN_TO_JAMO.entries.associate { (key, jamo) -> jamo to key }

    // 초성 19개 (유니코드 조합 순서)
    private val CHO = listOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )
    private val CHO_INDEX: Map<Char, Int> = CHO.withIndex().associate { (i, c) -> c to i }

    // 중성 21개 (기본 14개 직접 입력 + 겹모음 7개 조합)
    private val JUNG = listOf(
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ', 'ㅙ',
        'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ',
    )
    private val JUNG_INDEX: Map<Char, Int> = JUNG.withIndex().associate { (i, c) -> c to i }

    // 종성 28개 (0=받침 없음)
    private val JONG: List<Char?> = listOf(
        null, 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 'ㄻ',
        'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 'ㅆ',
        'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )
    private val JONG_INDEX: Map<Char, Int> = JONG.withIndex()
        .filter { it.value != null }
        .associate { (i, c) -> c!! to i }

    // 종성으로 쓰일 수 있는 단일 자음 (ㄸㅃㅉ 제외 16개)
    private val JONG_SINGLE_CHARS: Set<Char> = setOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )

    // 겹받침 조합 (자음 2개 -> 겹받침 1개)
    private val JONG_COMPOUND: Map<Pair<Char, Char>, Char> = mapOf(
        ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
        ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
        ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ', ('ㄹ' to 'ㅎ') to 'ㅀ',
        ('ㅂ' to 'ㅅ') to 'ㅄ',
    )
    private val JONG_DECOMPOSE: Map<Char, Pair<Char, Char>> = JONG_COMPOUND.entries.associate { (k, v) -> v to k }

    // 겹모음 조합 (모음 2개 -> 겹모음 1개)
    private val JUNG_COMPOUND: Map<Pair<Char, Char>, Char> = mapOf(
        ('ㅗ' to 'ㅏ') to 'ㅘ',
        ('ㅗ' to 'ㅐ') to 'ㅙ',
        ('ㅗ' to 'ㅣ') to 'ㅚ',
        ('ㅜ' to 'ㅓ') to 'ㅝ',
        ('ㅜ' to 'ㅔ') to 'ㅞ',
        ('ㅜ' to 'ㅣ') to 'ㅟ',
        ('ㅡ' to 'ㅣ') to 'ㅢ',
    )
    private val JUNG_DECOMPOSE: Map<Char, Pair<Char, Char>> = JUNG_COMPOUND.entries.associate { (k, v) -> v to k }

    private const val HANGUL_BASE = 0xAC00
    private val HANGUL_SYLLABLE_RANGE = 0xAC00..0xD7A3
    private val HANGUL_COMPAT_JAMO_RANGE = 0x3131..0x318E
    private val ASCII_LETTER_REGEX = Regex("^[a-zA-Z ]+$")

    /**
     * 영문(QWERTY) 입력을 2벌식 자판 기준 한글로 변환한다. 예: "spdlqj" -> "네이버".
     * 매핑 안 되는 문자가 섞여 있으면 원문을 그대로 반환한다.
     */
    fun en2ko(text: String): String {
        val jamos = text.map { keyToJamo(it) ?: return text }

        val output = StringBuilder()
        var cho: Char? = null
        var jung: Char? = null
        var jongFirst: Char? = null
        var jongSecond: Char? = null

        fun hasBlock() = cho != null || jung != null

        fun flush() {
            if (cho != null && jung != null) {
                val choIdx = CHO_INDEX.getValue(cho!!)
                val jungIdx = JUNG_INDEX.getValue(jung!!)
                val jongChar = if (jongSecond != null) JONG_COMPOUND[jongFirst!! to jongSecond!!] else jongFirst
                val jongIdx = jongChar?.let { JONG_INDEX.getValue(it) } ?: 0
                output.append((HANGUL_BASE + (choIdx * 21 + jungIdx) * 28 + jongIdx).toChar())
            } else {
                // 초성만 있거나 모음만 있는 불완전 조합 — 낱자 그대로 출력
                cho?.let { output.append(it) }
                jung?.let { output.append(it) }
            }
            cho = null
            jung = null
            jongFirst = null
            jongSecond = null
        }

        for (jamo in jamos) {
            if (jamo in CHO_INDEX) {
                when {
                    !hasBlock() -> cho = jamo
                    cho != null && jung == null -> {
                        flush()
                        cho = jamo
                    }
                    jongFirst == null && jamo in JONG_SINGLE_CHARS -> jongFirst = jamo
                    jongFirst != null && jongSecond == null && JONG_COMPOUND.containsKey(jongFirst!! to jamo) -> jongSecond = jamo
                    else -> {
                        flush()
                        cho = jamo
                    }
                }
            } else {
                when {
                    !hasBlock() -> jung = jamo
                    cho != null && jung == null -> jung = jamo
                    jongFirst == null && JUNG_COMPOUND.containsKey(jung!! to jamo) -> jung = JUNG_COMPOUND.getValue(jung!! to jamo)
                    jongFirst != null && jongSecond == null -> {
                        // 받침 1개를 다음 음절 초성으로 이월
                        val movedCho = jongFirst!!
                        jongFirst = null
                        flush()
                        cho = movedCho
                        jung = jamo
                    }
                    jongFirst != null && jongSecond != null -> {
                        // 겹받침 중 뒤 자모를 다음 음절 초성으로 이월
                        val movedCho = jongSecond!!
                        jongSecond = null
                        flush()
                        cho = movedCho
                        jung = jamo
                    }
                    else -> {
                        flush()
                        jung = jamo
                    }
                }
            }
        }
        flush()
        return output.toString()
    }

    /**
     * 한글 입력을 2벌식 자판 기준 영문(QWERTY)으로 변환한다. 예: "메ㅔㅣㄷ" -> "apple".
     */
    fun ko2en(text: String): String {
        val output = StringBuilder()
        for (ch in text) {
            val offset = ch.code - HANGUL_BASE
            if (ch.code in HANGUL_SYLLABLE_RANGE) {
                val choIdx = offset / (21 * 28)
                val jungIdx = (offset / 28) % 21
                val jongIdx = offset % 28

                appendJamoOrCompound(output, CHO[choIdx])
                appendJamoOrCompound(output, JUNG[jungIdx])
                JONG[jongIdx]?.let { appendJamoOrCompound(output, it) }
            } else {
                appendJamoOrCompound(output, ch)
            }
        }
        return output.toString()
    }

    /**
     * 입력이 자판 미스매칭으로 보일 때만 변환 결과를 반환한다. 그 외(혼합 문자, 숫자/기호 포함,
     * 변환 결과가 원문과 동일)에는 null을 반환해 무의미한 재검색을 막는다.
     */
    fun convertIfMislayout(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val isAllHangul = trimmed.all {
            it == ' ' || it.code in HANGUL_SYLLABLE_RANGE || it.code in HANGUL_COMPAT_JAMO_RANGE
        }
        val converted = when {
            ASCII_LETTER_REGEX.matches(trimmed) -> en2ko(trimmed)
            isAllHangul -> ko2en(trimmed)
            else -> null
        } ?: return null

        return converted.takeIf { it != trimmed }
    }

    private fun keyToJamo(ch: Char): Char? = EN_TO_JAMO[ch] ?: EN_TO_JAMO[ch.lowercaseChar()]

    private fun appendJamoOrCompound(output: StringBuilder, jamo: Char) {
        val decomposed = JUNG_DECOMPOSE[jamo] ?: JONG_DECOMPOSE[jamo]
        if (decomposed != null) {
            output.append(JAMO_TO_EN[decomposed.first] ?: decomposed.first)
            output.append(JAMO_TO_EN[decomposed.second] ?: decomposed.second)
        } else {
            output.append(JAMO_TO_EN[jamo] ?: jamo)
        }
    }
}
