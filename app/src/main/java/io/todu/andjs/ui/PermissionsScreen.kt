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
    var overlay by remember {mutableStateOf(Settings.canDrawOverlays(context))}
    var notifications by remember {mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())}
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {notifications=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled();(context.applicationContext as io.todu.andjs.AndJsApplication).externalFlow=false}
    var error by remember {mutableStateOf<String?>(null)}
    val health=remember {HealthApi(context)}
    val scope=rememberCoroutineScope()
    var healthStatus by remember {mutableStateOf<Map<String,Any>>(emptyMap())}
    fun refreshHealth() {scope.launch {try {healthStatus=health.historyStatus()} catch(_:Exception) {healthStatus=emptyMap()}}}
    LaunchedEffect(Unit) {refreshHealth()}
    val healthPermissions=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {refreshHealth()}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) {status=host.permissions();overlay=Settings.canDrawOverlays(context);notifications=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled();io.todu.andjs.widget.ScriptWidgetProvider.refresh(context);refreshHealth()}}
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
        Text("权限授权",fontSize=22.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
    }}) {padding->Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        PermissionSection("连接与设备")
        ConnectionPermissions()
        PermissionSection("系统与通知")
        if(status["watch"]!=true) PermissionRow("悬浮窗","在其他应用上显示运行窗口",overlay) {open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)}
        PermissionRow("通知","脚本通知与运行提醒",notifications) {
            if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permissionFlow(context) {notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
        }
        PermissionRow("系统操作","最近任务与通知栏操作",status["accessibility"]==true) {
            try {context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))} catch(_:Exception) {error="此设备没有无障碍设置"}
        }
        PermissionRow("修改系统设置","亮度、息屏时间与屏幕旋转",status["writeSettings"]==true) {open(Settings.ACTION_MANAGE_WRITE_SETTINGS)}
        PermissionSection("文件与健康")
        FitnessPermissionButton()
        if(status["watch"]!=true) PermissionRow("共享文件访问","读写手机共享文件",status["sharedFiles"]==true) {
            if(Build.VERSION.SDK_INT>=30) open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            else permissionFlow(context) {storage.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,android.Manifest.permission.WRITE_EXTERNAL_STORAGE))}
        }
        if(status["watch"]!=true) PermissionRow("健康数据",when(healthStatus["status"]) {"available"->"步数、心率与睡眠记录";"installOrUpdate"->"需要安装或更新 Health Connect";else->"此设备暂不支持 Health Connect"},(healthStatus["granted"] as? List<*>)?.size==6) {
            try {
                when(health.availability()) {
                    HealthConnectClient.SDK_AVAILABLE->healthPermissions.launch(HealthApi.permissions)
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED->context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    else->error="此设备暂不支持 Health Connect"
                }
            } catch(_:Exception) {error="无法打开健康授权，请检查 Health Connect"}
        }
        Text("网络与应用文件目录无需额外授权。",modifier=Modifier.padding(horizontal=6.dp,vertical=10.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }}
}
@Composable private fun PermissionRow(title:String,description:String,granted:Boolean,onClick:(()->Unit)?) {
    val icon=when(title) {"悬浮窗"->"app-window";"通知"->"bell";"系统操作"->"shield";"修改系统设置"->"settings";"共享文件访问"->"folder";"健康数据"->"heart";else->"global"}
    PermissionCard(title,description,icon,granted,onClick=onClick)
}
