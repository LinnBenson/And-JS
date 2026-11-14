package io.todu.andjs.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun ConnectionPermissions(preview:Boolean=false) {
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
    OutlinedButton(onClick={request("location",arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))},modifier=Modifier.fillMaxWidth()) {
        Text(when {preview->"位置授权";granted(Manifest.permission.ACCESS_FINE_LOCATION)->"位置 · 精确";granted(Manifest.permission.ACCESS_COARSE_LOCATION)->"位置 · 大致";else->"位置授权"})
    }
    if(Build.VERSION.SDK_INT>=31 && (preview || context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))) OutlinedButton(onClick={request("bluetooth",arrayOf(Manifest.permission.BLUETOOTH_CONNECT))},modifier=Modifier.fillMaxWidth()) {Text(if(granted(Manifest.permission.BLUETOOTH_CONNECT)) "蓝牙 · 已授权" else "蓝牙授权")}
    message?.let {Text(it)}
}
