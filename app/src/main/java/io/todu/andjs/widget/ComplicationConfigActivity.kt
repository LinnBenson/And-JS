package io.todu.andjs.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import io.todu.andjs.AndJsApplication
import io.todu.andjs.ui.Glyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComplicationConfigActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val app=application as AndJsApplication
        val slot=intent.getIntExtra(ComplicationDataSourceService.EXTRA_CONFIG_COMPLICATION_ID,-1)
        if(!app.isWatch || slot<0 || intent.action!=WatchComplicationSlots.CONFIG_ACTION) {finish();return}
        setContent {
            val snapshot by app.watchStore.state.collectAsState()
            var saving by remember {mutableStateOf(false)}
            var error by remember {mutableStateOf<String?>(null)}
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFB6FF80),background=Color.Black,surface=Color(0xFF20242B))) {
                Surface(Modifier.fillMaxSize(),color=Color.Black) {
                    LazyColumn(contentPadding=PaddingValues(horizontal=24.dp,vertical=28.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        item {IconButton(onClick={finish()},enabled=!saving) {Glyph("back",MaterialTheme.colorScheme.primary,Modifier.size(22.dp),"返回")}}
                        item {Text("选择快捷方式",fontSize=16.sp)}
                        if(snapshot.scripts.isEmpty()) item {Text("请先从手机同步脚本",fontSize=13.sp)}
                        error?.let {message->item {Text(message,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}}
                        items(snapshot.scripts,key={it.item.id}) {document->
                            Surface(onClick={
                                if(!saving) {
                                    saving=true;error=null
                                    lifecycleScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {WatchComplicationSlots.select(this@ComplicationConfigActivity,slot,document.item.id)}
                                            runCatching {WatchComplicationSlots.refresh(this@ComplicationConfigActivity)}
                                            setResult(RESULT_OK);finish()
                                        } catch(e:kotlinx.coroutines.CancellationException) {throw e}
                                        catch(_:Exception) {error="保存失败，请重试";saving=false}
                                    }
                                }
                            },enabled=!saving,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
                                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Glyph(document.item.icon,MaterialTheme.colorScheme.primary,Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(document.item.name,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
