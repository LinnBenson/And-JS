package io.todu.andjs.runtime

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import java.util.UUID

/** Only app-owned temporary rules are removed; other applications' rules remain intact. */
internal object ScriptDnd {
    data class Token(val rule:String?,val uri:Uri?,val previous:Int,val requested:Int)
    @Synchronized fun start(context:Context,mode:String):Token {
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!manager.isNotificationPolicyAccessGranted) throw deviceError("PERMISSION_DENIED","请先授予勿扰模式访问权限")
        val filter=when(mode) {"priority"->NotificationManager.INTERRUPTION_FILTER_PRIORITY;"alarms"->NotificationManager.INTERRUPTION_FILTER_ALARMS;"none"->NotificationManager.INTERRUPTION_FILTER_NONE;else->throw deviceError("INVALID_OPTIONS","mode 须为 priority、alarms 或 none")}
        if(Build.VERSION.SDK_INT>=29) {
            val uri=Uri.parse("condition://${context.packageName}/script-dnd/${UUID.randomUUID()}")
            val rule=AutomaticZenRule("And JS · 临时勿扰",null,ComponentName(context,DndRuleActivity::class.java),uri,null,filter,true)
            val id=manager.addAutomaticZenRule(rule) ?: throw deviceError("UNSUPPORTED","系统未接受勿扰规则")
            val token=Token(id,uri,0,filter)
            try {manager.setAutomaticZenRuleState(id,Condition(uri,"脚本运行中",Condition.STATE_TRUE));return token}
            catch(e:Exception) {runCatching {manager.removeAutomaticZenRule(id)};throw e}
        }
        val previous=manager.currentInterruptionFilter
        if(previous==NotificationManager.INTERRUPTION_FILTER_UNKNOWN) throw deviceError("UNSUPPORTED","无法读取当前勿扰状态")
        val prefs=context.getSharedPreferences("script-dnd-recovery",Context.MODE_PRIVATE)
        check(prefs.edit().putInt("previous",previous).putInt("requested",filter).commit()) {"无法保存原勿扰状态"}
        val token=Token(null,null,previous,filter)
        try {manager.setInterruptionFilter(filter);return token} catch(e:Exception) {runCatching {stop(context,token)};throw e}
    }
    @Synchronized fun stop(context:Context,token:Token) {
        val manager=context.getSystemService(NotificationManager::class.java)
        if(token.rule!=null && Build.VERSION.SDK_INT>=29) {
            try {manager.setAutomaticZenRuleState(token.rule,Condition(token.uri!!,"脚本已结束",Condition.STATE_FALSE))}
            finally {manager.removeAutomaticZenRule(token.rule)}
        } else {
            if(manager.currentInterruptionFilter==token.requested) manager.setInterruptionFilter(token.previous)
            context.getSharedPreferences("script-dnd-recovery",Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Synchronized fun recover(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!manager.isNotificationPolicyAccessGranted) return
        if(Build.VERSION.SDK_INT>=29) {
            manager.automaticZenRules.filterValues {it.conditionId?.authority==context.packageName && it.conditionId?.path?.startsWith("/script-dnd/")==true}.forEach {(id,r)->runCatching {stop(context,Token(id,r.conditionId,0,r.interruptionFilter))}}
        } else {
            val prefs=context.getSharedPreferences("script-dnd-recovery",Context.MODE_PRIVATE)
            if(prefs.contains("previous")) runCatching {stop(context,Token(null,null,prefs.getInt("previous",NotificationManager.INTERRUPTION_FILTER_ALL),prefs.getInt("requested",0)))}
        }
    }
}
