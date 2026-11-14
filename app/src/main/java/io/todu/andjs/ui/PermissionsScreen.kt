package io.todu.andjs.ui

import kotlinx.coroutines.launch
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import io.todu.andjs.runtime.HealthApi
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.todu.andjs.runtime.AndroidPlatformApi

@Composable fun PermissionsScreen(onBack:()->Unit) {
    val context=LocalContext.current
    val host=remember {AndroidPlatformApi(context)}
    var status by remember {mutableStateOf(host.permissions())}
    var error by remember {mutableStateOf<String?>(null)}
    val health=remember {HealthApi(context)}
    val scope=rememberCoroutineScope()
    var healthStatus by remember {mutableStateOf<Map<String,Any>>(emptyMap())}
    fun refreshHealth() {scope.launch {try {healthStatus=health.historyStatus()} catch(_:Exception) {healthStatus=emptyMap()}}}
    LaunchedEffect(Unit) {refreshHealth()}
    val healthPermissions=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {refreshHealth()}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) {status=host.permissions();refreshHealth()}}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    val storage=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {(context.applicationContext as io.todu.andjs.AndJsApplication).externalFlow=false;status=host.permissions()}
    fun open(action:String) {
        try {context.startActivity(Intent(action,Uri.parse("package:${context.packageName}")))}
        catch(e:Exception) {error="此设备没有对应的授权页面"}
    }
    BackHandler(onBack=onBack)
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={Row(Modifier.statusBarsPadding().height(60.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack) {Glyph("back",MaterialTheme.colorScheme.onSurface,Modifier.size(22.dp),"返回")}
        Text("权限授权",fontSize=18.sp)
    }}) {padding->Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        FitnessPermissionButton()
        ConnectionPermissions()
        PermissionRow("网络访问","用于 HTTPS 请求，无需手动授权",true,null)
        PermissionRow("修改系统设置","亮度、息屏时间及屏幕旋转；依设备支持",status["writeSettings"]==true) {open(Settings.ACTION_MANAGE_WRITE_SETTINGS)}
        if(status["watch"]!=true) PermissionRow("共享文件访问","允许脚本访问共享存储；仅手机",status["sharedFiles"]==true) {
            if(Build.VERSION.SDK_INT>=30) open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            else permissionFlow(context) {storage.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,android.Manifest.permission.WRITE_EXTERNAL_STORAGE))}
        }
        if(status["watch"]!=true) PermissionRow("健康数据",when(healthStatus["status"]) {"available"->"步数、心率、睡眠、距离、热量、体重";"installOrUpdate"->"需要安装或更新 Health Connect";else->"此设备暂不支持 Health Connect"},(healthStatus["granted"] as? List<*>)?.size==6) {
            try {
                when(health.availability()) {
                    HealthConnectClient.SDK_AVAILABLE->healthPermissions.launch(HealthApi.permissions)
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED->context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    else->error="此设备暂不支持 Health Connect"
                }
            } catch(_:Exception) {error="无法打开健康授权，请检查 Health Connect"}
        }
        Text("应用文件目录无需授权。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }}
}
@Composable private fun PermissionRow(title:String,description:String,granted:Boolean,onClick:(()->Unit)?) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {Text(title,fontSize=15.sp);Text(description,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(onClick==null) Text("已允许",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            else TextButton(onClick=onClick) {Text(if(granted) "已授权" else "授权")}
        }
    }
}
