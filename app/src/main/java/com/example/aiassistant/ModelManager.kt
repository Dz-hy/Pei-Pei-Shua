package com.example.aiassistant

import android.content.Context
import org.json.JSONArray

object ModelManager {
    private var models = mutableListOf<AiModelConfig>()
    private var loaded = false

    val allModels: List<AiModelConfig> get() = models.toList()

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val json = AppPreferences.getAiModels(context)
        if (json.isNotEmpty()) {
            models = mutableListOf<AiModelConfig>().also {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    it.add(AiModelConfig.fromJson(arr.getJSONObject(i)))
                }
            }
        }
        // 没有模型则创建默认
        if (models.isEmpty()) {
            models.add(AiModelConfig(
                name = "默认模型",
                baseUrl = AppPreferences.getApiBaseUrl(context),
                apiKey = AppPreferences.getApiKey(context),
                model = AppPreferences.getApiModel(context)
            ))
            save(context)
        }
    }

    fun get(id: String) = models.find { it.id == id }

    fun add(context: Context, config: AiModelConfig) {
        models.add(config)
        save(context)
    }

    fun update(context: Context, config: AiModelConfig) {
        val idx = models.indexOfFirst { it.id == config.id }
        if (idx >= 0) {
            models[idx] = config
            save(context)
        }
    }

    fun delete(context: Context, id: String) {
        models.removeAll { it.id == id }
        save(context)
    }

    /** 调整模型顺序（备用模型切换优先级；主模型由 activeModelId 决定，仍最优先）。 */
    fun move(context: Context, fromIndex: Int, toIndex: Int) {
        if (fromIndex !in models.indices || toIndex !in models.indices || fromIndex == toIndex) return
        models.add(toIndex, models.removeAt(fromIndex))
        save(context)
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (m in models) arr.put(m.toJson())
        AppPreferences.setAiModels(context, arr.toString())
    }
}
