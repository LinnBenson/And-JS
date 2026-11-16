package io.todu.andjs.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.todu.andjs.runtime.FitnessApi

@Composable fun WatchPermissionsScreen(preview:Boolean,onBack:()->Unit) {
    val context=LocalContext.current
    val fitness=remember(context) {FitnessApi(context)}
    var status by remember {mutableStateOf(if(preview) emptyMap() else fitness.status())}
    var writeSettings by remember {mutableStateOf(!preview && Settings.System.canWrite(context))}
    var notifications by remember {mutableStateOf(!preview && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())}
    var accessibility by remember {mutableStateOf(!preview && io.todu.andjs.runtime.SystemActionsService.available())}
    var denied by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        (context.applicationContext as io.todu.andjs.AndJsApplication).externalFlow=false
        status=fitness.status();denied=it.values.any {granted->!granted}
    }
    val notifyLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        (context.applicationContext as io.todu.andjs.AndJsApplication).externalFlow=false
    }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,preview) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME && !preview) {status=fitness.status();writeSettings=Settings.System.canWrite(context);notifications=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled();accessibility=io.todu.andjs.runtime.SystemActionsService.available()}}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    fun open(action:String) {
        if(preview) {message="请在手表上授权";return}
        try {context.startActivity(Intent(action,Uri.parse("package:${context.packageName}")))}
        catch(_:Exception) {message="此设备没有对应的授权页面"}
    }
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(start=22.dp,end=22.dp,top=46.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.padding(bottom=8.dp)) {
            Glyph("shield",MaterialTheme.colorScheme.primary,Modifier.size(22.dp))
            Text("权限授权",fontSize=16.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium)
        }}
        item {ConnectionPermissions(preview,compact=true)}
        item {PermissionCard("系统操作","","shield",accessibility,compact=true,status=if(preview) "在手表上授权" else if(accessibility) "已授权" else "去授权") {
            if(preview) message="请在手表上授权"
            else try {context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))} catch(_:Exception) {message="此设备没有无障碍设置"}
        }}
        item {
            val supported=status["steps"]==true || status["heartRate"]==true
            val allowed=supported && (status["steps"]!=true || status["stepsGranted"]==true) && (status["heartRate"]!=true || status["heartRateGranted"]==true)
            val detail=when {preview->"步数与心率";!supported->"设备不支持";allowed->"已授权";status["stepsGranted"]==true || status["heartRateGranted"]==true->"部分授权";else->"去授权"}
            PermissionCard("健身传感器","","heart",allowed,compact=true,status=detail,onClick=if(!preview && !supported) null else ({
                if(preview) message="请在手表上授权"
                else if(allowed || denied || fitness.permissions().isEmpty()) open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                else permissionFlow(context) {launcher.launch(fitness.permissions())}
            }))
        }
        item {PermissionCard("系统设置","","settings",writeSettings,compact=true,status=if(preview) "亮度与息屏" else if(writeSettings) "已授权" else "去授权") {open(Settings.ACTION_MANAGE_WRITE_SETTINGS)}}
        item {PermissionCard("通知","","bell",notifications,compact=true,status=if(preview) "在手表上授权" else if(notifications) "已授权" else "去授权") {
            if(preview) message="请在手表上授权"
            else if(android.os.Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permissionFlow(context) {notifyLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
        }}
        item {PermissionCard("应用权限","","shield",false,compact=true,status="管理全部权限") {open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)}}
        message?.let {item {Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=12.dp))}}
    }
    Row(Modifier.fillMaxWidth().padding(bottom=4.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        WatchBackButton(onClick=onBack)
    }
    }
}
