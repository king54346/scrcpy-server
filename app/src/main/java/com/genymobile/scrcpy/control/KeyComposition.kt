package com.genymobile.scrcpy.control

/**
 * 分解带重音符号的字符
 *
 * 例如，[decompose('é')] 返回 `"\u0301e"`
 *
 * 这对于注入键盘事件以生成期望的字符非常有用（[android.view.KeyCharacterMap.getEvents]
 * 使用输入 `"é"` 时返回 `null`，但使用输入 `"\u0301e"` 时正常工作）。
 *
 * 参考 [diacritical dead key characters](https://source.android.com/devices/input/key-character-map-files#behaviors)
 */
object KeyComposition {
    // Unicode 组合字符（死键）
    private const val KEY_DEAD_GRAVE = "\u0300"      // 重音符 `
    private const val KEY_DEAD_ACUTE = "\u0301"      // 锐音符 ´
    private const val KEY_DEAD_CIRCUMFLEX = "\u0302" // 抑扬符 ^
    private const val KEY_DEAD_TILDE = "\u0303"      // 波浪符 ~
    private const val KEY_DEAD_UMLAUT = "\u0308"     // 分音符 ¨

    // 字符分解映射表，延迟初始化以提高性能
    private val COMPOSITION_MAP by lazy { createDecompositionMap() }

    /**
     * 分解带重音符号的字符为组合字符序列
     *
     * @param c 要分解的字符
     * @return 分解后的字符串（组合字符 + 基础字符），如果字符无法分解则返回 null
     */
    fun decompose(c: Char): String? {
        return COMPOSITION_MAP[c]
    }

    // region 重音符号构造器

    /** 构造重音符组合 */
    private fun grave(c: Char): String = KEY_DEAD_GRAVE + c

    /** 构造锐音符组合 */
    private fun acute(c: Char): String = KEY_DEAD_ACUTE + c

    /** 构造抑扬符组合 */
    private fun circumflex(c: Char): String = KEY_DEAD_CIRCUMFLEX + c

    /** 构造波浪符组合 */
    private fun tilde(c: Char): String = KEY_DEAD_TILDE + c

    /** 构造分音符组合 */
    private fun umlaut(c: Char): String = KEY_DEAD_UMLAUT + c

    // endregion

    /**
     * 创建字符分解映射表
     *
     * @return 包含所有支持字符分解规则的不可变映射
     */
    private fun createDecompositionMap(): Map<Char, String> {
        return mutableMapOf<Char, String>().apply {
            // 重音符字符
            putAll(listOf(
                'À' to grave('A'), 'È' to grave('E'), 'Ì' to grave('I'),
                'Ò' to grave('O'), 'Ù' to grave('U'), 'à' to grave('a'),
                'è' to grave('e'), 'ì' to grave('i'), 'ò' to grave('o'),
                'ù' to grave('u'), 'Ǹ' to grave('N'), 'ǹ' to grave('n'),
                'Ẁ' to grave('W'), 'ẁ' to grave('w'), 'Ỳ' to grave('Y'),
                'ỳ' to grave('y')
            ))

            // 锐音符字符
            putAll(listOf(
                'Á' to acute('A'), 'É' to acute('E'), 'Í' to acute('I'),
                'Ó' to acute('O'), 'Ú' to acute('U'), 'Ý' to acute('Y'),
                'á' to acute('a'), 'é' to acute('e'), 'í' to acute('i'),
                'ó' to acute('o'), 'ú' to acute('u'), 'ý' to acute('y'),
                'Ć' to acute('C'), 'ć' to acute('c'), 'Ĺ' to acute('L'),
                'ĺ' to acute('l'), 'Ń' to acute('N'), 'ń' to acute('n'),
                'Ŕ' to acute('R'), 'ŕ' to acute('r'), 'Ś' to acute('S'),
                'ś' to acute('s'), 'Ź' to acute('Z'), 'ź' to acute('z'),
                'Ǵ' to acute('G'), 'ǵ' to acute('g'), 'Ḉ' to acute('Ç'),
                'ḉ' to acute('ç'), 'Ḱ' to acute('K'), 'ḱ' to acute('k'),
                'Ḿ' to acute('M'), 'ḿ' to acute('m'), 'Ṕ' to acute('P'),
                'ṕ' to acute('p'), 'Ẃ' to acute('W'), 'ẃ' to acute('w')
            ))

            // 抑扬符字符
            putAll(listOf(
                'Â' to circumflex('A'), 'Ê' to circumflex('E'), 'Î' to circumflex('I'),
                'Ô' to circumflex('O'), 'Û' to circumflex('U'), 'â' to circumflex('a'),
                'ê' to circumflex('e'), 'î' to circumflex('i'), 'ô' to circumflex('o'),
                'û' to circumflex('u'), 'Ĉ' to circumflex('C'), 'ĉ' to circumflex('c'),
                'Ĝ' to circumflex('G'), 'ĝ' to circumflex('g'), 'Ĥ' to circumflex('H'),
                'ĥ' to circumflex('h'), 'Ĵ' to circumflex('J'), 'ĵ' to circumflex('j'),
                'Ŝ' to circumflex('S'), 'ŝ' to circumflex('s'), 'Ŵ' to circumflex('W'),
                'ŵ' to circumflex('w'), 'Ŷ' to circumflex('Y'), 'ŷ' to circumflex('y'),
                'Ẑ' to circumflex('Z'), 'ẑ' to circumflex('z')
            ))

            // 波浪符字符
            putAll(listOf(
                'Ã' to tilde('A'), 'Ñ' to tilde('N'), 'Õ' to tilde('O'),
                'ã' to tilde('a'), 'ñ' to tilde('n'), 'õ' to tilde('o'),
                'Ĩ' to tilde('I'), 'ĩ' to tilde('i'), 'Ũ' to tilde('U'),
                'ũ' to tilde('u'), 'Ẽ' to tilde('E'), 'ẽ' to tilde('e'),
                'Ỹ' to tilde('Y'), 'ỹ' to tilde('y')
            ))

            // 分音符字符
            putAll(listOf(
                'Ä' to umlaut('A'), 'Ë' to umlaut('E'), 'Ï' to umlaut('I'),
                'Ö' to umlaut('O'), 'Ü' to umlaut('U'), 'ä' to umlaut('a'),
                'ë' to umlaut('e'), 'ï' to umlaut('i'), 'ö' to umlaut('o'),
                'ü' to umlaut('u'), 'ÿ' to umlaut('y'), 'Ÿ' to umlaut('Y'),
                'Ḧ' to umlaut('H'), 'ḧ' to umlaut('h'), 'Ẅ' to umlaut('W'),
                'ẅ' to umlaut('w'), 'Ẍ' to umlaut('X'), 'ẍ' to umlaut('x'),
                'ẗ' to umlaut('t')
            ))
        }.toMap() // 转换为不可变映射以确保线程安全
    }
}