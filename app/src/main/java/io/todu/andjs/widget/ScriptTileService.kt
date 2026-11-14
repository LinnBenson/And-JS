package io.todu.andjs.widget

import androidx.wear.tiles.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import androidx.wear.protolayout.ActionBuilders.*
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.todu.andjs.AndJsApplication
import io.todu.andjs.R
import io.todu.andjs.ui.AppIcons
import io.todu.andjs.ui.scriptColorPair

/** The system renders this tile; JS runs only after the user opens an item. */
class ScriptTileService:TileService() {
    private fun items()=(application as AndJsApplication).watchStore.state.value.scripts.map {it.item}.filter {it.widget}.take(8)
    private fun click(id:String?):Clickable {
        val activity=AndroidActivity.Builder().setPackageName(packageName).setClassName("io.todu.andjs.MainActivity")
        if(id!=null) activity.addKeyToExtraMapping(ScriptWidgetProvider.TOKEN,AndroidStringExtra.Builder().setValue(ScriptWidgetProvider.token(this)).build())
        if(id!=null) activity.addKeyToExtraMapping(ScriptWidgetProvider.RUN_ID,AndroidStringExtra.Builder().setValue(id).build())
        return Clickable.Builder().setId(id ?: "app").setOnClick(LaunchAction.Builder().setAndroidActivity(activity.build()).build()).build()
    }
    override fun onTileRequest(requestParams:RequestBuilders.TileRequest):ListenableFuture<TileBuilders.Tile> {
        val items=items()
        val size=minOf(requestParams.deviceConfiguration.screenWidthDp,requestParams.deviceConfiguration.screenHeightDp).toFloat().coerceIn(160f,500f)
        val ring=Box.Builder().setWidth(dp(size-8)).setHeight(dp(size-8))
        items.forEachIndexed {index,item->
            val angle=if(items.size==1) 0f else (index+.5f)*360f/items.size
            // Round caps extend beyond both arc endpoints; reserve their width too.
            val radius=(size-8-28)/2f
            val gap=Math.toDegrees(2.0*kotlin.math.asin((28.0+6.0)/(2.0*radius))).toFloat()
            val sweep=360f/items.size-gap
            val colors=scriptColorPair(item.color)
            ring.addContent(Arc.Builder().setAnchorAngle(degrees(angle)).setAnchorType(ARC_ANCHOR_CENTER)
                .addContent(ArcLine.Builder().setLength(degrees(sweep)).setThickness(dp(28f)).setColor(argb(colors.first.toInt()))
                    .setModifiers(ArcModifiers.Builder().setClickable(click(item.id)).build()).build()).build())
            val image=Image.Builder().setResourceId("icon-${item.id}").setWidth(dp(20f)).setHeight(dp(20f))
                .setColorFilter(ColorFilter.Builder().setTint(argb(colors.second.toInt())).build())
                .setModifiers(Modifiers.Builder().setClickable(click(item.id)).setSemantics(Semantics.Builder().setContentDescription(item.name).build()).build()).build()
            ring.addContent(Arc.Builder().setAnchorAngle(degrees(angle)).setAnchorType(ARC_ANCHOR_CENTER)
                .addContent(ArcAdapter.Builder().setRotateContents(false).setContent(Box.Builder().setWidth(dp(28f)).setHeight(dp(28f)).addContent(image).build()).build()).build())
        }
        ring.addContent(Image.Builder().setResourceId("app").setWidth(dp(44f)).setHeight(dp(44f))
            .setModifiers(Modifiers.Builder().setClickable(click(null)).setSemantics(Semantics.Builder().setContentDescription("打开 And JS").build()).build()).build())
        val root=Box.Builder().setWidth(expand()).setHeight(expand()).setModifiers(Modifiers.Builder().setBackground(Background.Builder().setColor(argb(0xFF000000.toInt())).build()).build()).addContent(ring.build()).build()
        return Futures.immediateFuture(TileBuilders.Tile.Builder().setResourcesVersion(version())
            .setTileTimeline(TimelineBuilders.Timeline.Builder().addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(Layout.Builder().setRoot(root).build()).build()).build()).build())
    }
    private fun version()=io.todu.andjs.wear.WatchSnapshot.hash("ring-v4:"+items().joinToString {"${it.id}:${it.icon}:${it.color}"})
    override fun onTileResourcesRequest(requestParams:RequestBuilders.ResourcesRequest):ListenableFuture<ResourceBuilders.Resources> {
        val resources=ResourceBuilders.Resources.Builder().setVersion(version())
        resources.addIdToImageMapping("app",ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.app_badge).build()).build())
        items().forEach {item->resources.addIdToImageMapping("icon-${item.id}",ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(AppIcons.resource(item.icon) ?: R.drawable.ic_code).build()).build())}
        return Futures.immediateFuture(resources.build())
    }
}
