package com.example.aiassistant.skills

import android.content.Context
import com.example.aiassistant.QuestionType
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 工具调用（Function Calling）核心：
 * ToolRegistry 持有全部已注册工具，OpenAIApiService 的工具循环在
 * AI 请求调用工具时执行它们并把结果回填到对话中。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String   // JSON 字符串形式的参数
)

data class ToolResult(
    val toolCallId: String,
    val content: String     // 回传给 AI 的工具执行结果文本
)

/**
 * 单个工具定义 + 执行逻辑。
 * @param applicableTypes 可用题型，null = 全部题型可用
 */
abstract class SkillTool(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject,
    val applicableTypes: Set<QuestionType>? = null
) {
    /** 执行工具，返回给 AI 的结果文本 */
    abstract fun execute(context: Context, arguments: JSONObject): String

    /** 生成 OpenAI function 定义 */
    fun toOpenAiDefinition(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parametersSchema)
        })
    }
}

object ToolRegistry {

    private val tools = linkedMapOf<String, SkillTool>()

    @Synchronized
    fun register(tool: SkillTool) {
        tools[tool.name] = tool
    }

    @Synchronized
    fun hasTools(): Boolean = tools.isNotEmpty()

    /** 按题型过滤后的 OpenAI tools 数组 */
    @Synchronized
    fun toOpenAiToolsArrayForType(questionType: QuestionType): JSONArray {
        val arr = JSONArray()
        for (tool in tools.values) {
            if (tool.applicableTypes == null || tool.applicableTypes.contains(questionType)) {
                arr.put(tool.toOpenAiDefinition())
            }
        }
        return arr
    }

    /** 执行一次工具调用（在后台线程被 OpenAIApiService 调用） */
    @Synchronized
    fun execute(context: Context, call: ToolCall): ToolResult {
        val tool = tools[call.name]
        if (tool == null) {
            return ToolResult(call.id, "错误：未注册的工具 $call.name")
        }
        return try {
            val args = if (call.arguments.isNullOrBlank()) JSONObject()
            else JSONObject(call.arguments)
            ToolResult(call.id, tool.execute(context, args))
        } catch (e: Exception) {
            ToolResult(call.id, "工具执行失败：${e.message}")
        }
    }
}
