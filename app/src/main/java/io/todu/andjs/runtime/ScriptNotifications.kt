package io.todu.andjs.runtime

import android.app.*
import android.content.Context
import io.todu.andjs.R
import org.json.JSONArray

object ScriptNotifications {
    @Synchronized private fun nextId(context:Context):Int {
        val prefs=context.getSharedPreferences("script-notifications",Context.MODE_PRIVATE)
        val previous=prefs.getInt("sequence",10000)
        val next=if(previous==Int.MAX_VALUE) 10001 else previous+1
        check(prefs.edit().putInt("sequence",next).commit()) {"无法保存通知编号"}
        return next
    }
    fun call(context:Context,method:String,args:JSONArray):Any {
        val manager=context.getSystemService(NotificationManager::class.java)
        fun id(value:Any):Int {require(value is Number && value.toDouble()%1==0.0 && value.toDouble() in 1.0..2147483647.0) {"通知 id 必须为正整数"};return value.toInt()}
        if(method=="cancelNotification") {manager.cancel("script",id(args.get(0)));return true}
        val title=args.get(0);val content=args.opt(1) ?: ""
        require(title is String && title.isNotBlank() && title.length<=200) {"通知标题须为 1–200 字符"}
        require(content is String && content.length<=4000) {"通知内容须为字符串，最多 4000 字符"}
        check(manager.areNotificationsEnabled()) {"请先在权限授权中允许通知"}
        val channel="script-messages"
        manager.createNotificationChannel(NotificationChannel(channel,"脚本通知",NotificationManager.IMPORTANCE_DEFAULT))
        check(manager.getNotificationChannel(channel).importance!=NotificationManager.IMPORTANCE_NONE) {"脚本通知渠道已关闭"}
        val notificationId=if(args.length()>2) id(args.get(2)) else nextId(context)
        manager.notify("script",notificationId,Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_code).setContentTitle(title).setContentText(content).setStyle(Notification.BigTextStyle().bigText(content)).setAutoCancel(true).setContentIntent(io.todu.andjs.widget.ScriptWidgetProvider.launchActivity(context)).build())
        return notificationId
    }
}
