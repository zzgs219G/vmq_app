package com.xixin.vmq

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xixin.vmq.keepalive.ListenerStatus
import com.xixin.vmq.keepalive.Watchdog
import com.xixin.vmq.store.AppStore
import com.xixin.vmq.ui.MonitorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 崩溃捕获必须在任何业务代码之前注册
        com.xixin.vmq.crash.CrashHandler.init(this)

        AppStore.init(this)

        // 用户打开应用 = 明确的「我希望它工作」信号，把意图置为 true。
        // 这样进程被系统回收后，看门狗与开机自启才有依据把监听链路恢复。
        Watchdog.setShouldRun(this, true)

        // 启动看门狗：9 分钟一次的 AlarmManager 定时自检。
        // 只在这里（以及服务连上、开机、看门狗自己触发时）安排，不做轮询。
        Watchdog.schedule(this)

        // 请求 Android 13+ 通知权限（用于展示推送成功提示与常驻状态）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val isDarkTheme = isSystemInDarkTheme()

            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (isDarkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MonitorScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppStore.init(this)
        AppStore.updatePermission(this)
        // 回到前台时补种闹钟：Doze 期间闹钟可能被系统丢弃，
        // 每次前台恢复都重新上一次钟，是从"单发"变成"自愈"的关键一环。
        if (Watchdog.shouldRun(this)) Watchdog.rearmIfStale(this)
        // 用户可能在设置里刚改了监听权限，这里同步一下
        ListenerStatus.permissionGranted(this)
    }
}
