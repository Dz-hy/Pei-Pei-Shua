package com.example.aiassistant.skills

import android.content.Context
import com.example.aiassistant.questionbank.QuestionBankManager
import org.json.JSONObject

/**
 * 内置工具集：
 * - get_solving_skill        查询行测各题型的解题技巧与方法
 * - get_typical_special_rule 查询各题型的高频考点与易错陷阱
 * - query_question_bank      检索本地题库中的相似真题
 */
object BuiltInTools {

    fun registerAll() {
        ToolRegistry.register(SolvingSkillTool())
        ToolRegistry.register(SpecialRuleTool())
        ToolRegistry.register(QuestionBankTool())
    }
}

// ── 工具一：解题技巧 ──────────────────────────────────────────────────

private class SolvingSkillTool : SkillTool(
    name = "get_solving_skill",
    description = "查询公务员考试行测指定题型的系统解题技巧与推荐作答方法。" +
        "当遇到没有把握的题型时调用，question_type 传中文题型名（如：片段阅读、逻辑填空、语句表达、图形推理、定义判断、类比推理、逻辑判断）。",
    parametersSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("question_type", JSONObject().apply {
                put("type", "string")
                put("description", "题型中文名称")
            })
        })
        put("required", org.json.JSONArray().put("question_type"))
    }
) {
    override fun execute(context: Context, arguments: JSONObject): String {
        val type = arguments.optString("question_type", "").trim()
        val skill = SolvingKnowledge.solvingSkills[type]
            ?: SolvingKnowledge.solvingSkills.entries.firstOrNull { type.contains(it.key) }?.value
            ?: return "未知的题型「$type」。可用题型：片段阅读、逻辑填空、语句表达、图形推理、定义判断、类比推理、逻辑判断"
        return "【$type · 解题技巧】\n$skill"
    }
}

// ── 工具二：专项考点 / 特殊规则 ───────────────────────────────────────

private class SpecialRuleTool : SkillTool(
    name = "get_typical_special_rule",
    description = "查询行测指定题型的高频考点、命题规律与易错陷阱。" +
        "question_type 传中文题型名（如：片段阅读、逻辑填空、语句表达、图形推理、定义判断、类比推理、逻辑判断）。",
    parametersSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("question_type", JSONObject().apply {
                put("type", "string")
                put("description", "题型中文名称")
            })
        })
        put("required", org.json.JSONArray().put("question_type"))
    }
) {
    override fun execute(context: Context, arguments: JSONObject): String {
        val type = arguments.optString("question_type", "").trim()
        val rule = SolvingKnowledge.specialRules[type]
            ?: SolvingKnowledge.specialRules.entries.firstOrNull { type.contains(it.key) }?.value
            ?: return "未知的题型「$type」。可用题型：片段阅读、逻辑填空、语句表达、图形推理、定义判断、类比推理、逻辑判断"
        return "【$type · 高频考点与易错陷阱】\n$rule"
    }
}

// ── 工具三：本地题库检索 ──────────────────────────────────────────────

private class QuestionBankTool : SkillTool(
    name = "query_question_bank",
    description = "在本地真题题库中检索与给定题目文字最相似的真题（含标准答案与解析）。" +
        "当题目可能为历年真题时调用，把题干关键文字（含选项关键词）作为 query 传入。",
    parametersSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "题目关键文字片段，建议包含题干核心句和选项关键词")
            })
        })
        put("required", org.json.JSONArray().put("query"))
    }
) {
    override fun execute(context: Context, arguments: JSONObject): String {
        if (!QuestionBankManager.isLoaded()) return "本地题库尚未加载完成"
        val query = arguments.optString("query", "").trim()
        if (query.isEmpty()) return "参数 query 不能为空"

        val question = QuestionBankManager.search(query) ?: return "本地题库中未检索到相似真题"

        return buildString {
            appendLine("【题库命中】")
            appendLine("题干：${question.stem}")
            if (question.options.isNotEmpty()) {
                appendLine("选项：")
                question.options.forEachIndexed { i, opt ->
                    appendLine("${'A' + i}. ${opt.text}")
                }
            }
            appendLine("答案：${question.answer}")
            if (question.analysis.isNotBlank()) appendLine("解析：${question.analysis.take(500)}")
            if (question.knowledgePoint.isNotBlank()) appendLine("知识点：${question.knowledgePoint}")
        }
    }
}

// ── 内置知识库内容 ────────────────────────────────────────────────────

private object SolvingKnowledge {

    val solvingSkills = mapOf(
        "片段阅读" to """
            1. 先看问法判断题型：主旨概括找中心，意图判断看言外之意，细节理解逐项比对原文。
            2. 主旨题抓关联词：转折（但是/然而）之后是重点；因果（因此/所以）结论是重点；必要条件（必须/只有…才）引出对策。
            3. 首尾句原则：总分结构看首句，分总结构看尾句；并列结构需全面概括。
            4. 主题词要贯穿：正确答案必须包含文段高频主题词，排除偏离主题的选项。
            5. 意图判断：有问题+对策优先选对策；纯客观说明文选准确概括项。
            6. 排除法优先排除：无中生有、过度推断、偷换概念、范围扩大缩小。
        """.trimIndent(),
        "逻辑填空" to """
            1. 先读语境找提示：解释关系（冒号、即、也就是说）、反对关系（但是、却）、递进关系（甚至、更）。
            2. 找呼应点：空格处与文中某词构成照应，选项必须与呼应点语义一致。
            3. 辨析词语差异：语义轻重、侧重点、感情色彩、搭配对象。
            4. 成语辨析看整体义，不逐字翻译；注意褒贬误用、对象误用。
            5. 关联词搭配：填关联词时先确认逻辑关系（转折/因果/递进/并列）再选。
            6. 两空题优先从把握大的空入手排除选项。
        """.trimIndent(),
        "语句表达" to """
            1. 语句排序：先定首句（背景引入/概念定义可作首句，指代不明、关联词后置的不作首句），再用代词、关联词、重复词语捆绑相邻句。
            2. 语句填空：空在句首=总起句概括全文；空在中间=承上启下；空在句尾=总结或对策。
            3. 病句辨析六大类：语序不当、搭配不当、成分残缺或赘余、结构混乱、表意不明、不合逻辑。
            4. 缩句提取主干检查搭配，注意一面/两面搭配（能否、是否）。
        """.trimIndent(),
        "图形推理" to """
            1. 属性规律：对称（轴对称/中心对称、对称轴方向数量）、曲直、开闭、黑白阴影。
            2. 数量规律：点（交点/切点）、线（笔画数/线条数）、角（直角数）、面（封闭区域数）、素（元素种类和个数）。
            3. 位置规律：平移（方向+步长）、旋转（方向+角度）、翻转；两组图类比看变化规律一致。
            4. 样式规律：叠加（去同存异/去异存同）、遍历、黑白运算。
            5. 空间折叠：相对面排除法、相邻面特征边/特征点法、画边法。
            6. 一笔画：奇点数为 0 或 2 的连通图可一笔画。
        """.trimIndent(),
        "定义判断" to """
            1. 提取定义要件：主体、客体、目的、原因、方式、结果，逐要件比对选项。
            2. 关键信息法：多定义题问哪个符合/不符合某一定义，只需对照该定义。
            3. 排除不符合要件的选项，宁缺毋滥：选项未提及的要件不能臆断其符合。
            4. 注意"不属于"型反向提问，看清否定词。
            5. 严谨遵循定义本身，不引入常识性补充解释。
        """.trimIndent(),
        "类比推理" to """
            1. 先造句析关系：语义（近义/反义/象征）、逻辑（全同/包含/交叉/并列/矛盾）、语法（主谓/动宾/偏正）、对应（功能/材料/职业/地点）。
            2. 包含关系辨析：种属关系（苹果：水果）用"是"造句验证；组成关系（轮胎：汽车）用"是…的一部分"验证。
            3. 二级辨析排除：当多个选项关系相同时，比情感色彩、词性、具体/抽象、自然/人工。
            4. 成语类比注意典故对应和语法结构。
        """.trimIndent(),
        "逻辑判断" to """
            1. 翻译推理：如果…那么→前推后；只有…才→后推前；除非…否则不=只有。连锁推理、逆否命题（否后推否前）。
            2. 矛盾关系：A 与 ¬A 必然一真一假；真假话问题先找矛盾再看其余。
            3. 分析推理：最大信息优先、确定信息入手，列表排除。
            4. 削弱加强：找论点和论据，削弱论点力度＞切断论证联系＞削弱论据；注意无关项和偷换论题。
            5. 前提假设：搭桥（论据与论点缺环）优先，必要条件用否定代入验证。
            6. 归纳推理警惕：绝对化表述（所有、必然）多为错误选项。
        """.trimIndent()
    )

    val specialRules = mapOf(
        "片段阅读" to """
            高频考点：转折结构主旨题、对策性意图题、细节判断中的"偷换时态/范围/概率"。
            易错陷阱：①把例子当主旨（例子服务于观点）；②"背景铺垫"首句非重点；③正确答案与原文表述的"同义替换"反而常对，字面完全一致的常是陷阱；④"作者未表态"类文段误选主观倾向项。
        """.trimIndent(),
        "逻辑填空" to """
            高频考点：解释类呼应、成语辨析、关联词填空。
            易错陷阱：①只看空格前后一小段忽略全文逻辑；②词语"听着顺口"而忽略语境呼应；③成语褒贬误用（如"殚精竭虑"褒义、"处心积虑"贬义）；④忽略程度递进（"甚至"前后语义递进）。
        """.trimIndent(),
        "语句表达" to """
            高频考点：语句排序的代词捆绑、句尾填空的总结句、病句中的一面/两面搭配。
            易错陷阱：①排序题只看首句不做句间捆绑验证；②"是否/能否"后面只能接两面表述；③并列结构中成分残缺（省略后不能搭配）；④指代不明的"这/此"置于首句。
        """.trimIndent(),
        "图形推理" to """
            高频考点：对称轴方向与数量、封闭区域数、一笔画、立体折叠。
            易错陷阱：①数线时忽略曲线；②对称轴只数条数不看方向变化规律；③黑白运算中"黑+白"规则记反；④折叠题相对面判断错误（间隔一个面才是相对面）。
        """.trimIndent(),
        "定义判断" to """
            高频考点：多定义辨析、要件比对中的"方式/目的"要件。
            易错陷阱：①用常识补充定义要件（必须严格按定义本身）；②"属于/不属于"看反；③选项表述绝对化但定义中无此限定；④主体要件不符（如定义主体是"政府"选项是"企业"）。
        """.trimIndent(),
        "类比推理" to """
            高频考点：种属与组成的区分、成语语法结构、职业与工具/场所对应。
            易错陷阱：①种属和组成混淆（"是"字验证法）；②交叉关系误判为并列；③近义词程度不同（如"失望：绝望"递进）；④忽略词性一致性。
        """.trimIndent(),
        "逻辑判断" to """
            高频考点：翻译推理的逆否等价、真假话的矛盾突破、削弱型论证结构分析。
            易错陷阱：①"如果…那么"误推为后推前；②否定前件/肯定后件的无效推理；③削弱题选了"不明确"或"无关比较"项；④前提假设题选了加强而非必要条件；⑤真假话题先假设而忽略先找矛盾。
        """.trimIndent()
    )
}
