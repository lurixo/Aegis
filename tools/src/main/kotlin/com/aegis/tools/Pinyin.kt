package com.aegis.tools

/** Host-side pinyin helpers: tone stripping and a canonical toneless-syllable inventory. */
object Pinyin {

    private val toneMap: Map<Char, Char> = buildMap {
        "āáǎà".forEach { put(it, 'a') }
        "ēéěèê".forEach { put(it, 'e') }
        "īíǐì".forEach { put(it, 'i') }
        "ōóǒò".forEach { put(it, 'o') }
        "ūúǔù".forEach { put(it, 'u') }
        "üǖǘǚǜ".forEach { put(it, 'v') } // ü -> v (full-pinyin input convention: lü -> lv)
        "ńňǹ".forEach { put(it, 'n') }
        put('ḿ', 'm')
    }

    /** Lowercase + strip tone marks; ü-family -> 'v'. Spaces preserved. */
    fun stripTones(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(toneMap[ch] ?: ch.lowercaseChar())
        return sb.toString()
    }

    /** True when [s] is a plausible toneless syllable (ascii a-z only). */
    fun isAsciiSyllable(s: String): Boolean = s.isNotEmpty() && s.all { it in 'a'..'z' }

    private val letterToDigit: Map<Char, Char> = buildMap {
        "abc".forEach { put(it, '2') }; "def".forEach { put(it, '3') }
        "ghi".forEach { put(it, '4') }; "jkl".forEach { put(it, '5') }
        "mno".forEach { put(it, '6') }; "pqrs".forEach { put(it, '7') }
        "tuv".forEach { put(it, '8') }; "wxyz".forEach { put(it, '9') }
    }

    /** Map a toneless letter key to its T9 phone-keypad digit key (a/b/c->2, ... w/x/y/z->9). */
    fun toT9(letters: String): String {
        val sb = StringBuilder(letters.length)
        for (c in letters) sb.append(letterToDigit[c] ?: c)
        return sb.toString()
    }

    /**
     * Canonical toneless Mandarin pinyin syllables (ü written as v for l/n; ju/qu/xu/yu keep u).
     * Used only to produce an advisory coverage diff against the wanxiang data — not a hard gate.
     */
    val canonicalSyllables: Set<String> = """
        a o e ê ai ei ao ou an en ang eng er
        yi ya yo ye yao you yan yin yang ying yong
        wu wa wo wai wei wan wen wang weng
        yu yue yuan yun
        ba bo bai bei bao ban ben bang beng bi bie biao bian bin bing bu
        pa po pai pei pao pou pan pen pang peng pi pie piao pian pin ping pu
        ma mo me mai mei mao mou man men mang meng mi mie miao miu mian min ming mu
        fa fo fei fou fan fen fang feng fu
        da de dai dei dao dou dan den dang deng dong di dia die diao diu dian ding du duo dui duan dun
        ta te tai tao tou tan tang teng tong ti tie tiao tian ting tu tuo tui tuan tun
        na ne nai nei nao nou nan nen nang neng nong ni nie niao niu nian nin niang ning nu nuo nuan nun nv nve
        la lo le lai lei lao lou lan lang leng long li lia lie liao liu lian lin liang ling lu luo luan lun lv lve
        ga ge gai gei gao gou gan gen gang geng gong gu gua guo guai gui guan gun guang
        ka ke kai kei kao kou kan ken kang keng kong ku kua kuo kuai kui kuan kun kuang
        ha he hai hei hao hou han hen hang heng hong hu hua huo huai hui huan hun huang
        ji jia jie jiao jiu jian jin jiang jing jiong ju jue juan jun
        qi qia qie qiao qiu qian qin qiang qing qiong qu que quan qun
        xi xia xie xiao xiu xian xin xiang xing xiong xu xue xuan xun
        zha zhe zhi zhai zhei zhao zhou zhan zhen zhang zheng zhong zhu zhua zhuo zhuai zhui zhuan zhun zhuang
        cha che chi chai chao chou chan chen chang cheng chong chu chua chuo chuai chui chuan chun chuang
        sha she shi shai shei shao shou shan shen shang sheng shu shua shuo shuai shui shuan shun shuang
        re ri rao rou ran ren rang reng rong ru rua ruo rui ruan run
        za ze zi zai zei zao zou zan zen zang zeng zong zu zuo zui zuan zun
        ca ce ci cai cao cou can cen cang ceng cong cu cuo cui cuan cun
        sa se si sai sao sou san sen sang seng song su suo sui suan sun
        n ng m hm hng
    """.trim().split(Regex("\\s+")).toSet()
}
