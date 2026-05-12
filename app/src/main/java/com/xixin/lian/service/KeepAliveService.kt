package com.xixin.lian.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xixin.lian.store.AppStore

class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 构建高优先级的常驻通知
        val notification = NotificationCompat.Builder(this, AppStore.CHANNEL_MONITOR)
            // 建议这里换成你 app 的真实 logo，比如 R.mipmap.ic_launcher，这里暂时用系统的盾牌
            .setSmallIcon(android.R.drawable.ic_secure) 
            .setContentTitle("V免签监控正在运行")
            .setContentText("保持后台持续运行，确保支付回调不漏单")
            .setOngoing(true) // 无法被用户滑动清除
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 配合 Channel 保证状态栏图标
            .build()
            
        // 绑定前台，数字 ID 不要用 0
        startForeground(999, notification)
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}