package io.todu.andjs.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun ConnectionPermissions(preview:Boolean=false,compact:Boolean=false) {
    val context=LocalContext.current
    var revision by remember {mutableIntStateOf(0)}
    var requested by remember {mutableStateOf(setOf<String>())}
    var message by remember {mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {(context.applicationContext as io.todu.andjs.AndJsApplication).externalFlow=false;revision++}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) revision++}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    fun granted(p:String)=!preview && revision.let {context.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED}
    fun request(kind:String,permissions:Array<String>) {
        if(preview) {message="请在真实设备上授权";return}
        try {
            if(kind in requested || permissions.all {granted(it)}) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))
            else {requested=requested+kind;permissionFlow(context) {launcher.launch(permissions)}}
        } catch(_:Exception) {message="无法打开授权页面"}
    }
    Column(verticalArrangement=Arrangement.spacedBy(if(compact) 8.dp else 10.dp)) {
        val fine=granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse=granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        PermissionCard("位置","定位与附近 Wi-Fi 扫描","map-pin",fine,compact,
            status=when {preview->"在手表上授权";fine->"精确位置";coarse->"大致位置";else->"去授权"}) {
            request("location",arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
        }
        if(Build.VERSION.SDK_INT>=33) {
            val allowed=granted(Manifest.permission.NEARBY_WIFI_DEVICES)
            PermissionCard("热点","创建局域网热点","wifi",allowed,compact,status=if(preview) "在手表上授权" else if(allowed) "已授权" else "去授权") {
                request("hotspot",arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
            }
        }
        if(Build.VERSION.SDK_INT>=31 && (preview || context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))) {
            val allowed=granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_ADVERTISE)
            PermissionCard("蓝牙","扫描、广播与蓝牙连接","bluetooth",allowed,compact,status=if(preview) "在手表上授权" else if(allowed) "已授权" else "去授权") {
                request("bluetooth",arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE))
            }
        }
        if(preview || context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            val allowed=granted(Manifest.permission.CAMERA)
            PermissionCard("手电筒","使用相机闪光灯","sun",allowed,compact,status=if(preview) "依设备支持" else if(allowed) "已授权" else "去授权") {
                request("camera",arrayOf(Manifest.permission.CAMERA))
            }
        }
        val notificationManager=context.getSystemService(android.app.NotificationManager::class.java)
        val policyAllowed=!preview && revision.let {notificationManager.isNotificationPolicyAccessGranted}
        PermissionCard("勿扰模式","脚本运行时临时开启","moon",policyAllowed,compact,status=if(preview) "在手表上授权" else if(policyAllowed) "已授权" else "去授权") {
            if(preview) message="请在真实设备上授权"
            else try {context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))} catch(_:Exception) {message="系统不支持此授权入口"}
        }
        val nfc=android.nfc.NfcAdapter.getDefaultAdapter(context)
        if(preview || nfc!=null) {
            val enabled=!preview && revision.let {nfc?.isEnabled==true}
            PermissionCard("NFC","读取与写入标签","radio",enabled,compact,status=if(preview) "依设备支持" else if(enabled) "已开启" else "去开启") {
                if(preview) message="请在真实设备上设置"
                else try {context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))} catch(_:Exception) {message="系统不支持此设置入口"}
            }
        }
        message?.let {Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
