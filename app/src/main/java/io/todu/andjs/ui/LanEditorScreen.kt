package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.runtime.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable internal fun LanEditorScreen(model:HomeViewModel,onBack:()->Unit) {
    val context=LocalContext.current.applicationContext
    val clipboard=LocalClipboardManager.current
    val view=LocalView.current
    val scope=rememberCoroutineScope()
    val holder=remember {AtomicReference<HttpServerSession?>(null)}
    val disposed=remember {AtomicBoolean(false)}
    val handler=remember {model.lanEditorHandler()}
    var port by rememberSaveable {mutableStateOf("8080")}
    var token by rememberSaveable {mutableStateOf("")}
    var url by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    DisposableEffect(Unit) {
        onDispose {disposed.set(true);holder.getAndSet(null)?.close();model.retry()}
    }
    DisposableEffect(url) {
        val previous=view.keepScreenOn
        if(url!=null)view.keepScreenOn=true
        onDispose {view.keepScreenOn=previous}
    }
    LaunchedEffect(url) {
        while(url!=null) {
            delay(500)
            holder.get()?.let {if(it.isClosed){error=it.failure;url=null;holder.compareAndSet(it,null)}}
        }
    }
    BackHandler(onBack=onBack)
    Scaffold(topBar={Row(Modifier.statusBarsPadding().fillMaxWidth().height(60.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick=onBack){Glyph("back",MaterialTheme.colorScheme.onSurface,Modifier.size(22.dp),"返回")}
        Text("局域网编辑",fontSize=18.sp)
    }}) {padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text("在电脑浏览器中编辑手机脚本",color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(port,{port=it},label={Text("端口")},singleLine=true,enabled=url==null&&!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
            OutlinedTextField(token,{token=it},label={Text("访问码（可留空）")},singleLine=true,enabled=url==null&&!busy,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            url?.let {address->
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(address)
                        TextButton(onClick={clipboard.setText(AnnotatedString(address))}){Text("复制地址")}
                    }
                }
                Text("保持此页面打开，离开后服务关闭。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={
                if(url!=null) {
                    busy=true
                    val server=holder.getAndSet(null);server?.close();url=null
                    scope.launch {withContext(Dispatchers.IO){server?.awaitClosed()};busy=false;model.retry()}
                } else {
                    val number=port.toIntOrNull()
                    if(number==null || number !in 1024..65535){error="端口须为 1024–65535";return@Button}
                    if(token.length>64 || token.any {it.code !in 33..126}){error="访问码最多 64 个非空格 ASCII 字符";return@Button}
                    error=null;busy=true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val server=AndroidHttpServer.open(context,HttpServerOptions(number,token,editor=handler::handle),RunControl())
                                holder.set(server)
                                if(disposed.get()){holder.compareAndSet(server,null);server.close();server.awaitClosed()}
                            }
                            url=holder.get()?.url
                        } catch(e:CancellationException){holder.getAndSet(null)?.close();throw e}
                        catch(e:Exception){error=e.message ?: "启动失败"}
                        finally {busy=false}
                    }
                }
            }) {Text(if(busy) "处理中…" else if(url!=null) "停止服务" else "启动服务")}
        }
    }
}
