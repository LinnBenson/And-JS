package io.todu.andjs.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import io.todu.andjs.AndJsApplication
import io.todu.andjs.MainActivity
import io.todu.andjs.R
import io.todu.andjs.domain.Script
import io.todu.andjs.ui.AppIcons
import io.todu.andjs.ui.scriptColorPair
import kotlinx.coroutines.runBlocking

/** Host-rendered static views. No timer, worker or script engine runs in a widget. */
open class ScriptWidgetProvider:AppWidgetProvider() {
    protected open val columns=2
    protected open val rows=2
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==AppWidgetManager.ACTION_APPWIDGET_UPDATE || intent.action==AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED) {
            val pending=goAsync()
            Thread {
                try {
                    val manager=AppWidgetManager.getInstance(context)
                    val ids=manager.getAppWidgetIds(ComponentName(context,javaClass))
                    if(ids.isNotEmpty()) {
                        val items=runBlocking {(context.applicationContext as AndJsApplication).repository.load()}.filter {it.widget}
                        ids.forEach {id->manager.updateAppWidget(id,layout(context,items,columns,rows))}
                    }
                } catch(e:Exception) {android.util.Log.w("AndJS","Widget update failed",e)} finally {pending.finish()}
            }.start()
        } else super.onReceive(context,intent)
    }
    companion object {
        const val TOKEN="andjs.widget.token"
        @Synchronized fun token(context:Context):String {
            val prefs=context.getSharedPreferences("widget-launch",Context.MODE_PRIVATE)
            return prefs.getString("token",null) ?: java.util.UUID.randomUUID().toString().also {check(prefs.edit().putString("token",it).commit())}
        }
        fun requestedId(context:Context,intent:Intent):String? = if(intent.getStringExtra(TOKEN)==token(context)) intent.getStringExtra(RUN_ID) else null
        const val RUN_ID="andjs.run.id"
        private val refreshHandler=android.os.Handler(android.os.Looper.getMainLooper())
        private var refreshTask:Runnable?=null
        @Synchronized fun refresh(context:Context) {
            val app=context.applicationContext
            refreshTask?.let {refreshHandler.removeCallbacks(it)}
            refreshTask=Runnable {refreshNow(app)}.also {refreshHandler.postDelayed(it,200)}
        }
        private fun refreshNow(context:Context) {
            val manager=AppWidgetManager.getInstance(context)
            listOf(ScriptWidgetProvider::class.java,LargeScriptWidgetProvider::class.java,HorizontalScriptWidgetProvider::class.java,VerticalScriptWidgetProvider::class.java,WideScriptWidgetProvider::class.java,PortraitScriptWidgetProvider::class.java,LandscapeScriptWidgetProvider::class.java).forEach {type->
                if(manager.getAppWidgetIds(ComponentName(context,type)).isNotEmpty()) context.sendBroadcast(Intent(context,type).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE))
            }
        }
        fun launch(context:Context,id:String?=null):PendingIntent {
            if(id!=null && !(context.applicationContext as AndJsApplication).isWatch && android.provider.Settings.canDrawOverlays(context)) {
                val intent=Intent(context,OverlayRunService::class.java).setData(android.net.Uri.parse("andjs://overlay/"+id)).putExtra(RUN_ID,id).putExtra(TOKEN,token(context))
                return PendingIntent.getForegroundService(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            return launchActivity(context,id)
        }
        fun launchActivity(context:Context,id:String?=null):PendingIntent {
            val intent=Intent(context,MainActivity::class.java).setAction(if(id==null) Intent.ACTION_MAIN else "io.todu.andjs.RUN_WIDGET")
                .setData(android.net.Uri.parse("andjs://widget/"+(id ?: "app")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if(id!=null) intent.putExtra(RUN_ID,id).putExtra(TOKEN,token(context))
            return PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        private fun layout(context:Context,items:List<Script>,columns:Int,rows:Int):RemoteViews {
            val root=RemoteViews(context.packageName,R.layout.script_widget)
            root.removeAllViews(R.id.widget_rows)
            val visible=items.take(columns*rows)
            root.setViewVisibility(R.id.widget_empty,if(visible.isEmpty()) View.VISIBLE else View.GONE)
            root.setOnClickPendingIntent(R.id.widget_empty,launch(context))
            for(row in 0 until rows) {
                val views=RemoteViews(context.packageName,R.layout.widget_row)
                for(col in 0 until 4) {
                    val container=listOf(R.id.widget_left,R.id.widget_right,R.id.widget_third,R.id.widget_fourth)[col]
                    if(col>=columns) {views.setViewVisibility(container,View.GONE);continue}
                    val slot=RemoteViews(context.packageName,R.layout.widget_item)
                    val item=visible.getOrNull(row*columns+col)
                    if(item==null) slot.setViewVisibility(R.id.widget_item,View.INVISIBLE) else {
                        val colors=scriptColorPair(item.color)
                        slot.setInt(R.id.widget_card_background,"setColorFilter",colors.first.toInt())
                        slot.setImageViewResource(R.id.widget_icon,AppIcons.resource(item.icon) ?: R.drawable.ic_code)
                        slot.setInt(R.id.widget_icon,"setColorFilter",colors.second.toInt())
                        slot.setTextColor(R.id.widget_name,colors.second.toInt())
                        slot.setTextViewText(R.id.widget_name,item.name)
                        slot.setContentDescription(R.id.widget_item,"运行${item.name}")
                        slot.setOnClickPendingIntent(R.id.widget_item,launch(context,item.id))
                    }
                    views.addView(container,slot)
                }
                root.addView(R.id.widget_rows,views)
            }
            return root
        }
    }
}
class LargeScriptWidgetProvider:ScriptWidgetProvider() {override val rows=4}
class HorizontalScriptWidgetProvider:ScriptWidgetProvider() {override val rows=1}
class VerticalScriptWidgetProvider:ScriptWidgetProvider() {override val columns=1}
class WideScriptWidgetProvider:ScriptWidgetProvider() {override val columns=4}

class PortraitScriptWidgetProvider:ScriptWidgetProvider() {override val rows=3}
class LandscapeScriptWidgetProvider:ScriptWidgetProvider() {override val columns=3}
