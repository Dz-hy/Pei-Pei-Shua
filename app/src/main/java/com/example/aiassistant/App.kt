package com.example.aiassistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class App : Application() {
    companion object {
        lateinit var instance: App
            private set

        /** 本 App 是否有界面在前台（用于回到 App 时自动收起悬浮球） */
        @Volatile var isAppUiVisible: Boolean = false
            private set
    }

    /** 已启动（未停止）的 Activity 计数 */
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        LaunchPerf.mark("App.onCreate start")

        // 在最早时机禁用 OpenMP 亲和性，防止 PaddleOCR 在 Android 16 上崩溃
        try {
            android.system.Os.setenv("KMP_AFFINITY", "none", true)
            android.system.Os.setenv("OMP_NUM_THREADS", "1", true)
            android.system.Os.setenv("OMP_PROC_BIND", "false", true)
            android.system.Os.setenv("GOMP_CPU_AFFINITY", "0", true)
            android.system.Os.setenv("OPENCV_FOR_THREADS_NUM", "1", true)
            android.util.Log.i("App", "OpenMP env vars set: KMP_AFFINITY=${android.system.Os.getenv("KMP_AFFINITY")}, OMP_NUM_THREADS=${android.system.Os.getenv("OMP_NUM_THREADS")}")
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to set OpenMP env vars: ${e.message}")
        }

        // 初始化 Skills 工具注册
        try {
            com.example.aiassistant.skills.BuiltInTools.registerAll()
        } catch (e: Exception) {
            android.util.Log.e("App", "Failed to register BuiltInTools: ${e.message}")
        }
        LaunchPerf.mark("App.onCreate end")

        // targetSdk 36 在 Android 15+ 强制 edge-to-edge，所有页面内容会画到状态栏/手势条下面。
        // 统一给每个 Activity 的根视图加上系统栏 + 刘海内边距（一次性适配全部页面）。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                isAppUiVisible = true
                // 回到本 App：收起悬浮球与小球，避免盖住自己的界面
                if (startedActivityCount == 1) {
                    ScreenCaptureService.instance?.hideBallForInApp()
                }
                val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
                val root = content.getChildAt(0) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                    val bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                    )
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
                ViewCompat.requestApplyInsets(root)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                // 离开本 App：恢复悬浮球
                if (startedActivityCount == 0) {
                    isAppUiVisible = false
                    ScreenCaptureService.instance?.restoreBallAfterInApp()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
