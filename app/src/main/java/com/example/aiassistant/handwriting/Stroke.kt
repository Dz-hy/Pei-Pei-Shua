package com.example.aiassistant.handwriting

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条手写笔画：笔刷编号 + 压平的点序列 [x0,y0,x1,y1,...]（内容区像素坐标）。
 * 序列化为 JSON 持久化，加载后可继续编辑、撤销任意历史笔画。
 */
data class Stroke(val brush: Int, val points: FloatArray) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("b", brush)
        val arr = JSONArray()
        for (v in points) arr.put(v.toDouble())
        put("p", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject?): Stroke? {
            if (obj == null) return null
            return try {
                val arr = obj.optJSONArray("p") ?: return null
                if (arr.length() < 2) return null
                val pts = FloatArray(arr.length())
                for (i in 0 until arr.length()) pts[i] = arr.optDouble(i).toFloat()
                Stroke(obj.optInt("b", 0), pts)
            } catch (_: Exception) {
                null
            }
        }

        fun listToJson(strokes: List<Stroke>): String {
            val arr = JSONArray()
            for (s in strokes) arr.put(s.toJson())
            return arr.toString()
        }

        fun listFromJson(json: String): MutableList<Stroke> {
            val result = mutableListOf<Stroke>()
            if (json.isBlank()) return result
            return try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    fromJson(arr.optJSONObject(i))?.let { result.add(it) }
                }
                result
            } catch (_: Exception) {
                mutableListOf()
            }
        }
    }
}
