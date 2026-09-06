package com.example.aiassistant

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * AI 模型故障转移执行器（统一封装）。
 *
 * 候选链：preferred 模型优先，其余模型按配置列表顺序兜底；视觉模式下识图模型前置。
 * 切换策略：仅网络/服务端类错误才切换下一个模型；鉴权等客户端错误（CLIENT）与
 *          请求构建失败（BUILD）直接终止——配置错误换模型也救不了。
 * 重试策略：NETWORK/SERVER/EMPTY 先在同模型上退避重试 2 次（2s/4s），仍失败才切换；
 *          429 限流在 OpenAIApiService 内部已重试 2 次，收到时直接切换。
 *
 * 回调统一在内部后台线程串行分发，UI 调用方需自行切主线程（runOnUiThread）。
 */
class AiFailoverExecutor private constructor(
    private val candidates: List<AiModelConfig>,
    private val request: (config: AiModelConfig, onComplete: (String) -> Unit, onError: (AiErrorKind, String) -> Unit) -> Unit,
    private val onComplete: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onModelSwitched: ((failed: AiModelConfig, reason: String, next: AiModelConfig) -> Unit)?,
    private val onModelAttemptFailed: ((failed: AiModelConfig, kind: AiErrorKind, reason: String) -> Unit)?
) {
    @Volatile private var cancelled = false
    private var modelIndex = 0
    private var sameModelRetry = 0

    /** 取消本次故障转移：丢弃后续重试/切换，并取消底层 HTTP 请求 */
    fun cancel() {
        cancelled = true
        OpenAIApiService.cancelCurrentRequest()
    }

    private fun launch() {
        if (cancelled) return
        val config = candidates.getOrNull(modelIndex)
        if (config == null) {
            onError("所有候选模型均请求失败")
            return
        }
        request(
            config,
            { text -> dispatch { if (!cancelled) onComplete(text) } },
            { kind, msg -> dispatch { if (!cancelled) handleFailure(kind, msg) } }
        )
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == deliveryHandler.looper) block() else deliveryHandler.post(block)
    }

    private fun handleFailure(kind: AiErrorKind, msg: String) {
        val failed = candidates.getOrNull(modelIndex) ?: return
        onModelAttemptFailed?.invoke(failed, kind, msg)

        if (!isSwitchable(kind)) {
            android.util.Log.e(TAG, "模型「${failed.name}」请求失败（不可切换错误 $kind），终止：$msg")
            onError(msg)
            return
        }

        if (isRetryableBeforeSwitch(kind) && sameModelRetry < MAX_SAME_MODEL_RETRY) {
            sameModelRetry++
            val delayMs = sameModelRetry * 2000L
            android.util.Log.w(TAG, "模型「${failed.name}」请求失败($kind)，${delayMs / 1000}s 后同模型重试：$msg")
            deliveryHandler.postDelayed({ if (!cancelled) launch() }, delayMs)
            return
        }

        val next = candidates.getOrNull(modelIndex + 1)
        if (next == null) {
            onError(msg)
            return
        }
        android.util.Log.w(TAG, "模型「${failed.name}」最终失败($kind)，切换备用「${next.name}」：$msg")
        onModelSwitched?.invoke(failed, msg, next)
        modelIndex++
        sameModelRetry = 0
        launch()
    }

    companion object {
        private const val TAG = "AiFailover"
        private const val MAX_SAME_MODEL_RETRY = 2

        private val deliveryThread = HandlerThread("AiFailoverExecutor").apply { start() }
        private val deliveryHandler = Handler(deliveryThread.looper)

        /** 该错误是否值得切换模型（客户端错误与构建失败不值得） */
        fun isSwitchable(kind: AiErrorKind): Boolean =
            kind != AiErrorKind.CLIENT && kind != AiErrorKind.BUILD

        /** 该错误是否先在同模型上重试再切换（429 已在服务层重试过，收到时直接切换） */
        fun isRetryableBeforeSwitch(kind: AiErrorKind): Boolean =
            kind == AiErrorKind.NETWORK || kind == AiErrorKind.SERVER || kind == AiErrorKind.EMPTY

        /**
         * 构建候选模型链：preferred 模型优先，其余按配置列表顺序兜底。
         * isVision=true 时识图模型前置（preferred 不支持识图时也放到识图组之后）。
         */
        fun buildChain(preferredId: String? = null, isVision: Boolean = false): List<AiModelConfig> {
            val all = ModelManager.allModels
            if (all.isEmpty()) return emptyList()
            val preferred = preferredId?.takeIf { it.isNotBlank() }?.let { ModelManager.get(it) }
            val rest = all.filter { it.id != preferred?.id }
            return if (isVision) {
                listOfNotNull(preferred?.takeIf { it.isVision }) +
                        rest.filter { it.isVision } +
                        listOfNotNull(preferred?.takeIf { !it.isVision }) +
                        rest.filter { !it.isVision }
            } else {
                listOfNotNull(preferred) + rest
            }
        }

        /**
         * 执行带故障转移的 AI 请求。
         * @param request 用给定模型发起请求：把 onComplete 接到 analyze* 的 onComplete，
         *                把 onError 接到 analyze* 的 onStructuredError
         * @param onError 候选链耗尽或遇到不可切换错误时的最终失败回调
         * @param onModelSwitched 每次成功切换备用模型时回调（可用于 Toast 提示）
         * @param onModelAttemptFailed 每次单模型请求失败时回调（重试/切换决策前，可用于
         *                             记录主模型失败原因、更新"正在重试"loading 文案）
         */
        fun execute(
            candidates: List<AiModelConfig>,
            request: (AiModelConfig, (String) -> Unit, (AiErrorKind, String) -> Unit) -> Unit,
            onComplete: (String) -> Unit,
            onError: (String) -> Unit,
            onModelSwitched: ((AiModelConfig, String, AiModelConfig) -> Unit)? = null,
            onModelAttemptFailed: ((AiModelConfig, AiErrorKind, String) -> Unit)? = null
        ): AiFailoverExecutor {
            val executor = AiFailoverExecutor(candidates, request, onComplete, onError, onModelSwitched, onModelAttemptFailed)
            executor.dispatch { executor.launch() }
            return executor
        }
    }
}
