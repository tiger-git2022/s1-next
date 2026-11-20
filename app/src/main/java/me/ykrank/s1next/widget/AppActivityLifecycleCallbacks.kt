package me.ykrank.s1next.widget

import android.app.Activity
import com.github.ykrank.androidtools.widget.net.WifiActivityLifecycleCallbacks
import me.ykrank.s1next.App
import me.ykrank.s1next.data.Wifi
import me.ykrank.s1next.widget.hostcheck.NoticeCheckTask
import me.ykrank.s1next.widget.net.ForegroundConnectionResetter
import javax.inject.Inject

class AppActivityLifecycleCallbacks(private val noticeCheckTask: NoticeCheckTask) :
    WifiActivityLifecycleCallbacks() {

    @Inject
    lateinit var wifi: Wifi

    @Inject
    lateinit var foregroundConnectionResetter: ForegroundConnectionResetter

    // Fork: 记录上一次 Wi-Fi 状态，仅在真实切换时清理旧网卡上的空闲连接
    private var lastWifiEnabled: Boolean? = null

    init {
        App.appComponent.inject(this)
    }

    override val wifiStateChangedCallback: ((Boolean) -> Unit)?
        get() = { enabled ->
            wifi.isWifiEnabled = enabled
            val prev = lastWifiEnabled
            lastWifiEnabled = enabled
            if (prev != null && prev != enabled) {
                foregroundConnectionResetter.onNetworkChanged()
            }
        }

    override fun onActivityStarted(activity: Activity) {
        val wasVisible = isAppVisible
        super.onActivityStarted(activity)
        if (!wasVisible && isAppVisible) {
            // Fork: 从后台切回前台时清理可能已失效的空闲连接
            foregroundConnectionResetter.onEnterForeground()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        noticeCheckTask.inspectCheckNoticeTask()
    }

    override fun onActivityStopped(activity: Activity) {
        val wasVisible = isAppVisible
        super.onActivityStopped(activity)
        if (wasVisible && !isAppVisible) {
            foregroundConnectionResetter.onEnterBackground()
        }
    }
}
