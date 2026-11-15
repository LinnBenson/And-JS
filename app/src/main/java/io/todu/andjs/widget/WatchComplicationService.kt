package io.todu.andjs.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.*
import io.todu.andjs.AndJsApplication
import io.todu.andjs.MainActivity
import io.todu.andjs.R
import io.todu.andjs.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Each system complication instance chooses its own synced script, independently of the tile. */
object WatchComplicationSlots {
    const val EXTRA_SLOT="andjs.complication.slot"
    const val CONFIG_ACTION="io.todu.andjs.CONFIGURE_COMPLICATION"
    private fun preferences(context:Context)=context.getSharedPreferences("watch-complications",Context.MODE_PRIVATE)
    fun selected(context:Context,slot:Int):String?=preferences(context).getString("slot-$slot",null)
    fun select(context:Context,slot:Int,id:String) {
        require(slot>=0 && id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        check(preferences(context).edit().putString("slot-$slot",id).commit()) {"保存失败，请重试"}
    }
    fun requestedScript(context:Context,intent:Intent):String? {
        if(!(context.applicationContext as AndJsApplication).isWatch) return null
        if(intent.getStringExtra(ScriptWidgetProvider.TOKEN)!=ScriptWidgetProvider.token(context)) return null
        val slot=intent.getIntExtra(EXTRA_SLOT,-1)
        return if(slot>=0) selected(context,slot) else null
    }
    fun tap(context:Context,slot:Int):PendingIntent {
        val intent=Intent(context,MainActivity::class.java)
            .setData(Uri.parse("andjs://complication/$slot"))
            .putExtra(EXTRA_SLOT,slot).putExtra(ScriptWidgetProvider.TOKEN,ScriptWidgetProvider.token(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun refresh(context:Context) {
        if(!(context.applicationContext as AndJsApplication).isWatch) return
        ComplicationDataSourceUpdateRequester.create(context,ComponentName(context,ScriptComplicationService::class.java)).requestUpdateAll()
    }
}

/** Icon-only data: the watch face controls the rendered size and tint, with no text or background. */
abstract class WatchComplicationService:SuspendingComplicationDataSourceService() {
    protected open val scriptShortcut=false
    override suspend fun onComplicationRequest(request:ComplicationRequest):ComplicationData?=withContext(Dispatchers.IO) {
        val app=application as AndJsApplication
        if(!app.isWatch) return@withContext null
        val script=if(scriptShortcut) WatchComplicationSlots.selected(this@WatchComplicationService,request.complicationInstanceId)?.let {id->
            app.watchStore.state.value.scripts.find {it.item.id==id}?.item
        } else null
        val resource=script?.let {AppIcons.resource(it.icon) ?: R.drawable.ic_code} ?: R.drawable.complication_app
        val tap=if(script!=null) WatchComplicationSlots.tap(this@WatchComplicationService,request.complicationInstanceId)
            else ScriptWidgetProvider.launchActivity(this@WatchComplicationService)
        data(request.complicationType,resource,script?.name ?: "打开 And JS",tap)
    }
    override fun getPreviewData(type:ComplicationType):ComplicationData?=
        data(type,if(scriptShortcut) R.drawable.ic_code else R.drawable.complication_app,
            if(scriptShortcut) "快捷方式" else "打开 And JS",null)

    private fun data(type:ComplicationType,resource:Int,description:String,tap:PendingIntent?):ComplicationData? {
        if(type!=ComplicationType.MONOCHROMATIC_IMAGE && type!=ComplicationType.SMALL_IMAGE) return null
        // Square canvas with equal padding; preserve drawable aspect ratio and avoid launcher masks.
        val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
        val drawable=checkNotNull(getDrawable(resource)).mutate()
        drawable.setTint(Color.WHITE)
        val ratio=drawable.intrinsicWidth.toFloat()/drawable.intrinsicHeight.coerceAtLeast(1)
        val width=if(ratio>=1f) 80 else (80*ratio).toInt().coerceAtLeast(1)
        val height=if(ratio>=1f) (80/ratio).toInt().coerceAtLeast(1) else 80
        drawable.setBounds((96-width)/2,(96-height)/2,(96+width)/2,(96+height)/2)
        drawable.draw(Canvas(bitmap))
        val icon=Icon.createWithBitmap(bitmap)
        val content=PlainComplicationText.Builder(description).build()
        return when(type) {
            ComplicationType.MONOCHROMATIC_IMAGE->MonochromaticImageComplicationData.Builder(
                MonochromaticImage.Builder(icon).setAmbientImage(icon).build(),content).setTapAction(tap).build()
            ComplicationType.SMALL_IMAGE->SmallImageComplicationData.Builder(
                SmallImage.Builder(icon,SmallImageType.ICON).setAmbientImage(icon).build(),content).setTapAction(tap).build()
            else->null
        }
    }
}
class AppComplicationService:WatchComplicationService()
class ScriptComplicationService:WatchComplicationService() {override val scriptShortcut=true}
