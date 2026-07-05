// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.engine

internal object SymbolAssociations {

    class Row(val name: String, val keys: String, vararg glyphs: String) {
        val glyphList: List<String> = glyphs.toList()
        val keyList: List<String> get() = keys.split(' ')
        val primaryKey: String get() = keys.substringBefore(' ')
    }

    class Exemption(val reason: String, vararg glyphs: String) {
        val glyphList: List<String> = glyphs.toList()
    }

    fun rows(): List<Row> =
        punctuationZh() +
        punctuationEn() +
        currency() +
        net() +
        math() +
        units() +
        greek() +
        arrows() +
        superSub() +
        ordinals()

    val exemptions: List<Exemption> = listOf(
        Exemption("参考记号(日式米印),中文无通用固定名", "※"),
        Exemption("重复记号(ditto),中文无通用词名", "〃"),
        Exemption("and 符号,中文无通用拼音词名", "&", "＆"),
        Exemption("URL 协议片段,无自然读音词", "://"),
        Exemption("历史货币符号 ECU,无通用中文名", "₠"),
        Exemption("汉字本身,词库候选直接可得(yuan)", "元", "圆"),
        Exemption("多义几何/代数圈号(圆/点积),无唯一通用名", "⊙"),
        Exemption("装饰性箭头变体(与→同义),无区分性自然名", "➜", "➤", "➔"),
        Exemption("法式引号(guillemets),中文无通用名", "»", "«", "‹", "›"),
        Exemption("上/下标运算符与字母变体,无自然中文词名", "⁺", "⁻", "⁼", "⁽", "⁾", "ⁿ", "ⁱ", "₊", "₋", "₌", "₍", "₎", "ₐ", "ₑ", "ₒ", "ₓ"),
        Exemption("点式序号变体(半角句点型),与圈N同义无区分性自然名", "⒈", "⒉", "⒊", "⒋", "⒌", "⒍", "⒎", "⒏", "⒐", "⒑"),
        Exemption("字母序号(圈A等),无纯拼音自然名", "Ⓐ", "Ⓑ", "Ⓒ", "ⓐ", "ⓑ", "ⓒ"),
        Exemption("国际音标符号/重音长音标记,无自然中文词名", "ɪ", "ɛ", "æ", "ə", "ɜ", "ʌ", "ɑ", "ɒ", "ɔ", "ʊ", "ø", "ð", "ʃ", "ʒ", "ŋ", "ʤ", "ʧ", "ç", "ɣ", "ʔ", "ɹ", "ɫ", "ɲ", "ˈ", "ˌ", "ː", "ˑ"),
        Exemption("拉丁字母本身(音标/拼音类目),26键直接可打", "i", "e", "o", "u", "y", "x", "a", "n"),
        Exemption("拼音字母及声调变体,键入其读音意图是打汉字", "ā", "á", "ǎ", "à", "ō", "ó", "ǒ", "ò", "ē", "é", "ě", "è", "ê", "ī", "í", "ǐ", "ì", "ū", "ú", "ǔ", "ù", "ü", "ǖ", "ǘ", "ǚ", "ǜ", "ń", "ň", "ǹ", "ḿ"),
        Exemption("半角 ASCII,同字全角孪生已由拼音关联保留,半角改从英文符号键/面板直出",
            ",", ";", ":", "?", "!", "(", ")", "~", "_", "#", "*", "@", "|", "/", "\\", "`"),
    )

    private fun punctuationZh(): List<Row> = listOf(
        Row("逗号", "douhao", "，"),
        Row("句号", "juhao", "。", "."),
        Row("顿号", "dunhao", "、"),
        Row("分号", "fenhao", "；"),
        Row("冒号", "maohao", "："),
        Row("问号", "wenhao", "？"),
        Row("叹号/感叹号", "tanhao gantanhao", "！"),
        Row("引号/双引号", "yinhao shuangyinhao", "“", "”", "\""),
        Row("单引号", "danyinhao", "‘", "’", "'"),
        Row("括号", "kuohao", "（", "）"),
        Row("左括号", "zuokuohao", "（"),
        Row("右括号", "youkuohao", "）"),
        Row("书名号", "shuminghao", "《", "》"),
        Row("单书名号", "danshuminghao", "〈", "〉"),
        Row("直角引号", "zhijiaoyinhao", "「", "」"),
        Row("双直角引号", "shuangzhijiaoyinhao", "『", "』"),
        Row("方头括号", "fangtoukuohao", "【", "】"),
        Row("空心方头括号", "kongxinfangtoukuohao", "〖", "〗"),
        Row("六角括号", "liujiaokuohao", "〔", "〕"),
        Row("省略号", "shenglvehao", "…"),
        Row("破折号", "pozhehao", "—"),
        Row("波浪号", "bolanghao", "～"),
        Row("间隔号", "jiangehao", "·"),
        Row("双竖线", "shuangshuxian", "‖"),
        Row("下划线", "xiahuaxian", "＿"),
        Row("波浪线", "bolangxian", "﹏", "﹋"),
        Row("井号", "jinghao", "＃"),
        Row("星号", "xinghao", "＊"),
        Row("艾特", "aite", "＠"),
        Row("百分号", "baifenhao", "％"),
        Row("加号", "jiahao", "＋"),
        Row("等号", "denghao", "＝"),
        Row("竖线/管道符", "shuxian guandaofu", "｜"),
        Row("小于号", "xiaoyuhao", "＜"),
        Row("大于号", "dayuhao", "＞"),
        Row("斜杠/斜线", "xiegang xiexian", "／"),
        Row("反斜杠/反斜线", "fanxiegang fanxiexian", "＼"),
        Row("反引号", "fanyinhao", "｀"),
    )

    private fun punctuationEn(): List<Row> = listOf(
        Row("中括号/方括号", "zhongkuohao fangkuohao", "[", "]"),
        Row("大括号/花括号", "dakuohao huakuohao", "{", "}"),
        Row("尖括号", "jiankuohao", "<", ">"),
        Row("脱字符", "tuozifu", "^"),
        Row("连字符", "lianzifu", "-"),
        Row("减号", "jianhao", "−", "-"),
        Row("项目符号/圆点", "xiangmufuhao yuandian", "•", "·"),
        Row("连接号", "lianjiehao", "–"),
        Row("章节号/分节号", "zhangjiehao fenjiehao", "§"),
        Row("段落号", "duanluohao", "¶"),
        Row("商标号/商标", "shangbiaohao shangbiao", "™", "®"),
        Row("版权号/版权", "banquanhao banquan", "©"),
        Row("注册商标", "zhuceshangbiao", "®"),
        Row("美金", "meijin", "\$"),
    )

    private fun currency(): List<Row> = listOf(
        Row("日元", "riyuan", "¥", "円"),
        Row("英镑", "yingbang", "£"),
        Row("韩元", "hanyuan", "₩"),
        Row("卢比/印度卢比", "lubi yindulubi", "₹"),
        Row("卢布", "lubu", "₽"),
        Row("里拉", "lila", "₺", "₤"),
        Row("泰铢", "taizhu", "฿"),
        Row("越南盾", "yuenandun", "₫"),
        Row("格里夫纳", "gelifuna", "₴"),
        Row("奈拉", "naila", "₦"),
        Row("美分", "meifen", "¢"),
        Row("比索", "bisuo", "₱"),
        Row("谢克尔", "xiekeer", "₪"),
        Row("坚戈", "jiange", "₸"),
        Row("图格里克", "tugelike", "₮"),
        Row("基普", "jipu", "₭"),
        Row("瓜拉尼", "gualani", "₲"),
        Row("科朗", "kelang", "₡"),
        Row("塞地", "saidi", "₵"),
        Row("比特币", "bitebi", "₿"),
        Row("里亚尔", "liyaer", "﷼"),
        Row("法郎", "falang", "₣"),
        Row("密尔", "mier", "₥"),
        Row("人民币", "renminbi", "￥"),
    )

    private fun net(): List<Row> = listOf(
        Row("网址", "wangzhi", "www.", "http://", "https://"),
    )

    private fun math(): List<Row> = listOf(
        Row("乘号", "chenghao", "×"),
        Row("叉/错号", "cha cuohao", "×"),
        Row("除号", "chuhao", "÷"),
        Row("不等于/不等号", "budengyu budenghao", "≠"),
        Row("约等于/约等号/约等", "yuedengyu yuedenghao yuedeng", "≈"),
        Row("恒等于/恒等号", "hengdengyu hengdenghao", "≡"),
        Row("正负号/正负", "zhengfuhao zhengfu", "±"),
        Row("负正号", "fuzhenghao", "∓"),
        Row("小于等于", "xiaoyudengyu", "≤"),
        Row("大于等于", "dayudengyu", "≥"),
        Row("无穷/无穷大/无限", "wuqiong wuqiongda wuxian", "∞"),
        Row("根号/平方根", "genhao pingfanggen", "√"),
        Row("对勾/对号", "duigou duihao", "√"),
        Row("立方根", "lifanggen", "∛"),
        Row("求和", "qiuhe", "∑"),
        Row("连乘/求积", "liancheng qiuji", "∏"),
        Row("积分/积分号", "jifen jifenhao", "∫"),
        Row("二重积分", "erchongjifen", "∬"),
        Row("三重积分", "sanchongjifen", "∭"),
        Row("环路积分/曲线积分", "huanlujifen quxianjifen", "∮"),
        Row("偏导/偏导数", "piandao piandaoshu", "∂"),
        Row("倒三角/纳布拉", "daosanjiao nabula", "∇"),
        Row("千分号/千分之", "qianfenhao qianfenzhi", "‰"),
        Row("正比于/成正比", "zhengbiyu chengzhengbi", "∝"),
        Row("所以", "suoyi", "∴"),
        Row("因为", "yinwei", "∵"),
        Row("角", "jiao", "∠"),
        Row("垂直", "chuizhi", "⊥"),
        Row("平行", "pingxing", "∥"),
        Row("角分", "jiaofen", "′"),
        Row("角秒", "jiaomiao", "″"),
        Row("圆周率", "yuanzhoulv", "π"),
        Row("二分之一", "erfenzhiyi", "½"),
        Row("三分之一", "sanfenzhiyi", "⅓"),
        Row("四分之一", "sifenzhiyi", "¼"),
        Row("四分之三", "sifenzhisan", "¾"),
        Row("三分之二", "sanfenzhier", "⅔"),
        Row("属于", "shuyu", "∈"),
        Row("不属于", "bushuyu", "∉"),
        Row("真包含于", "zhenbaohanyu", "⊂"),
        Row("真包含", "zhenbaohan", "⊃"),
        Row("包含于", "baohanyu", "⊆"),
        Row("包含", "baohan", "⊇"),
        Row("并集", "bingji", "∪"),
        Row("交集", "jiaoji", "∩"),
        Row("空集", "kongji", "∅"),
        Row("任意", "renyi", "∀"),
        Row("存在", "cunzai", "∃"),
        Row("全等", "quandeng", "≅"),
        Row("相似", "xiangsi", "∽"),
        Row("异或/直和", "yihuo zhihe", "⊕"),
        Row("张量积", "zhangliangji", "⊗"),
        Row("实数集", "shishuji", "ℝ"),
        Row("自然数集", "ziranshuji", "ℕ"),
        Row("整数集", "zhengshuji", "ℤ"),
        Row("有理数集", "youlishuji", "ℚ"),
        Row("复数集", "fushuji", "ℂ"),
        Row("正弦", "zhengxian", "sin"),
        Row("余弦", "yuxian", "cos"),
        Row("正切", "zhengqie", "tan"),
        Row("余切", "yuqie", "cot"),
        Row("正割", "zhengge", "sec"),
        Row("余割", "yuge", "csc"),
        Row("反正弦", "fanzhengxian", "arcsin"),
        Row("反余弦", "fanyuxian", "arccos"),
        Row("反正切", "fanzhengqie", "arctan"),
        Row("双曲正弦", "shuangquzhengxian", "sinh"),
        Row("双曲余弦", "shuangquyuxian", "cosh"),
        Row("双曲正切", "shuangquzhengqie", "tanh"),
    )

    private fun units(): List<Row> = listOf(
        Row("摄氏度", "sheshidu", "℃"),
        Row("华氏度", "huashidu", "℉"),
        Row("千克/公斤", "qianke gongjin", "㎏"),
        Row("毫米", "haomi", "㎜"),
        Row("厘米/公分", "limi gongfen", "㎝"),
        Row("千米/公里", "qianmi gongli", "㎞"),
        Row("平方米/平米", "pingfangmi pingmi", "㎡"),
        Row("立方米", "lifangmi", "㎥"),
        Row("毫克", "haoke", "㎎"),
        Row("毫升", "haosheng", "㎖"),
    )

    private fun greek(): List<Row> = listOf(
        Row("阿尔法", "aerfa", "α", "Α"),
        Row("贝塔", "beita", "β", "Β"),
        Row("伽马", "gama", "γ", "Γ"),
        Row("德尔塔", "deerta", "δ", "Δ", "∆"),
        Row("伊普西龙", "yipuxilong", "ε", "Ε"),
        Row("泽塔", "zeta", "ζ", "Ζ"),
        Row("伊塔", "yita", "η", "Η"),
        Row("西塔", "xita", "θ", "Θ"),
        Row("约塔", "yueta", "ι", "Ι"),
        Row("卡帕", "kapa", "κ", "Κ"),
        Row("拉姆达/兰姆达", "lamuda lanmuda", "λ", "Λ"),
        Row("缪", "miu", "μ", "Μ"),
        Row("希腊字母纽", "xilazimuniu", "ν", "Ν"),
        Row("克西", "kexi", "ξ", "Ξ"),
        Row("奥米克戎", "aomikerong", "ο", "Ο"),
        Row("派", "pai", "π", "Π"),
        Row("希腊字母柔", "xilazimurou", "ρ", "Ρ"),
        Row("西格玛", "xigema", "σ", "Σ", "ς"),
        Row("陶", "tao", "τ", "Τ"),
        Row("宇普西龙", "yupuxilong", "υ", "Υ"),
        Row("斐", "fei", "φ", "Φ"),
        Row("卡方", "kafang", "χ"),
        Row("希腊字母凯", "xilazimukai", "χ", "Χ"),
        Row("普西", "puxi", "ψ", "Ψ"),
        Row("欧米伽", "oumijia", "ω", "Ω"),
        Row("欧姆", "oumu", "Ω"),
    )

    private fun arrows(): List<Row> = listOf(
        Row("箭头", "jiantou", "→", "←", "↑"),
        Row("左箭头", "zuojiantou", "←", "⬅"),
        Row("右箭头", "youjiantou", "→", "➡"),
        Row("上箭头", "shangjiantou", "↑", "⬆"),
        Row("下箭头", "xiajiantou", "↓", "⬇"),
        Row("左右箭头", "zuoyoujiantou", "↔"),
        Row("上下箭头", "shangxiajiantou", "↕"),
        Row("左上箭头", "zuoshangjiantou", "↖"),
        Row("右上箭头", "youshangjiantou", "↗"),
        Row("右下箭头", "youxiajiantou", "↘"),
        Row("左下箭头", "zuoxiajiantou", "↙"),
        Row("双箭头", "shuangjiantou", "⇒", "⇐", "⇔"),
        Row("右双箭头", "youshuangjiantou", "⇒"),
        Row("左双箭头", "zuoshuangjiantou", "⇐"),
        Row("上双箭头", "shangshuangjiantou", "⇑"),
        Row("下双箭头", "xiashuangjiantou", "⇓"),
        Row("左右双箭头", "zuoyoushuangjiantou", "⇔"),
        Row("上下双箭头", "shangxiashuangjiantou", "⇕"),
        Row("左弯箭头", "zuowanjiantou", "↩"),
        Row("右弯箭头", "youwanjiantou", "↪"),
        Row("上弯箭头", "shangwanjiantou", "⤴"),
        Row("下弯箭头", "xiawanjiantou", "⤵"),
        Row("逆时针", "nishizhen", "↺"),
        Row("顺时针", "shunshizhen", "↻"),
        Row("长箭头", "changjiantou", "⟶", "⟵"),
    )

    private fun superSub(): List<Row> = listOf(
        Row("平方", "pingfang", "²"),
        Row("立方", "lifang", "³"),
        Row("上标0", "shangbiaoling", "⁰"),
        Row("上标1", "shangbiaoyi", "¹"),
        Row("上标2", "shangbiaoer", "²"),
        Row("上标3", "shangbiaosan", "³"),
        Row("上标4", "shangbiaosi", "⁴"),
        Row("上标5", "shangbiaowu", "⁵"),
        Row("上标6", "shangbiaoliu", "⁶"),
        Row("上标7", "shangbiaoqi", "⁷"),
        Row("上标8", "shangbiaoba", "⁸"),
        Row("上标9", "shangbiaojiu", "⁹"),
        Row("下标0", "xiabiaoling", "₀"),
        Row("下标1", "xiabiaoyi", "₁"),
        Row("下标2", "xiabiaoer", "₂"),
        Row("下标3", "xiabiaosan", "₃"),
        Row("下标4", "xiabiaosi", "₄"),
        Row("下标5", "xiabiaowu", "₅"),
        Row("下标6", "xiabiaoliu", "₆"),
        Row("下标7", "xiabiaoqi", "₇"),
        Row("下标8", "xiabiaoba", "₈"),
        Row("下标9", "xiabiaojiu", "₉"),
    )

    private fun ordinals(): List<Row> = listOf(
        Row("圈一~圈二十", "quanyi", "①"),
        Row("", "quaner", "②"),
        Row("", "quansan", "③"),
        Row("", "quansi", "④"),
        Row("", "quanwu", "⑤"),
        Row("", "quanliu", "⑥"),
        Row("", "quanqi", "⑦"),
        Row("", "quanba", "⑧"),
        Row("", "quanjiu", "⑨"),
        Row("", "quanshi", "⑩"),
        Row("", "quanshiyi", "⑪"),
        Row("", "quanshier", "⑫"),
        Row("", "quanshisan", "⑬"),
        Row("", "quanshisi", "⑭"),
        Row("", "quanshiwu", "⑮"),
        Row("", "quanshiliu", "⑯"),
        Row("", "quanshiqi", "⑰"),
        Row("", "quanshiba", "⑱"),
        Row("", "quanshijiu", "⑲"),
        Row("", "quanershi", "⑳"),
        Row("括号一~括号十(全角汉字/半角数字两式)", "kuohaoyi", "㈠", "⑴"),
        Row("", "kuohaoer", "㈡", "⑵"),
        Row("", "kuohaosan", "㈢", "⑶"),
        Row("", "kuohaosi", "㈣", "⑷"),
        Row("", "kuohaowu", "㈤", "⑸"),
        Row("", "kuohaoliu", "㈥", "⑹"),
        Row("", "kuohaoqi", "㈦", "⑺"),
        Row("", "kuohaoba", "㈧", "⑻"),
        Row("", "kuohaojiu", "㈨", "⑼"),
        Row("", "kuohaoshi", "㈩", "⑽"),
        Row("罗马数字一~十(1-5 带小写)", "luomashuziyi", "Ⅰ", "ⅰ"),
        Row("", "luomashuzier", "Ⅱ", "ⅱ"),
        Row("", "luomashuzisan", "Ⅲ", "ⅲ"),
        Row("", "luomashuzisi", "Ⅳ", "ⅳ"),
        Row("", "luomashuziwu", "Ⅴ", "ⅴ"),
        Row("", "luomashuziliu", "Ⅵ"),
        Row("", "luomashuziqi", "Ⅶ"),
        Row("", "luomashuziba", "Ⅷ"),
        Row("", "luomashuzijiu", "Ⅸ"),
        Row("", "luomashuzishi", "Ⅹ"),
    )
}
