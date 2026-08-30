package com.example.aiassistant.shizheng

/**
 * 习近平新时代中国特色社会主义思想四大体系及全部具体条目。
 * 用于新闻思想分类与题型二的选项生成。
 */
object ShizhengTaxonomy {

    const val SYS_TEN = "十个明确"
    const val SYS_FOURTEEN = "十四个坚持"
    const val SYS_THIRTEEN = "十三个方面成就"
    const val SYS_SIX = "六个必须坚持"

    val SYSTEMS = listOf(SYS_TEN, SYS_FOURTEEN, SYS_THIRTEEN, SYS_SIX)

    val ITEMS: Map<String, List<String>> = mapOf(
        SYS_TEN to listOf(
            "明确中国特色社会主义最本质的特征是中国共产党领导",
            "明确坚持和发展中国特色社会主义的总任务",
            "明确新时代我国社会主要矛盾",
            "明确中国特色社会主义事业总体布局是\"五位一体\"、战略布局是\"四个全面\"",
            "明确全面深化改革总目标",
            "明确全面推进依法治国总目标",
            "明确坚持和完善社会主义基本经济制度、推动高质量发展",
            "明确党在新时代的强军目标",
            "明确中国特色大国外交要推动构建人类命运共同体",
            "明确全面从严治党的战略方针"
        ),
        SYS_FOURTEEN to listOf(
            "坚持党对一切工作的领导",
            "坚持以人民为中心",
            "坚持全面深化改革",
            "坚持新发展理念",
            "坚持人民当家作主",
            "坚持全面依法治国",
            "坚持社会主义核心价值体系",
            "坚持在发展中保障和改善民生",
            "坚持人与自然和谐共生",
            "坚持总体国家安全观",
            "坚持党对人民军队的绝对领导",
            "坚持\"一国两制\"和推进祖国统一",
            "坚持推动构建人类命运共同体",
            "坚持全面从严治党"
        ),
        SYS_THIRTEEN to listOf(
            "坚持党的全面领导方面的成就",
            "全面从严治党方面的成就",
            "经济建设方面的成就",
            "全面深化改革开放方面的成就",
            "政治建设方面的成就",
            "全面依法治国方面的成就",
            "文化建设方面的成就",
            "社会建设方面的成就",
            "生态文明建设方面的成就",
            "国防和军队建设方面的成就",
            "维护国家安全方面的成就",
            "坚持\"一国两制\"和推进祖国统一方面的成就",
            "外交工作方面的成就"
        ),
        SYS_SIX to listOf(
            "必须坚持人民至上",
            "必须坚持自信自立",
            "必须坚持守正创新",
            "必须坚持问题导向",
            "必须坚持系统观念",
            "必须坚持胸怀天下"
        )
    )

    /** 拼接给 AI 的条目清单文本 */
    fun taxonomyPromptText(): String = buildString {
        SYSTEMS.forEach { sys ->
            append("【$sys】\n")
            ITEMS[sys]?.forEachIndexed { i, item -> append("${i + 1}. $item\n") }
            append("\n")
        }
    }.trimEnd()
}
