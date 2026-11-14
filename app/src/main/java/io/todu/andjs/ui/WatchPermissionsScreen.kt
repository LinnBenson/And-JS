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
    var denied by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        (context.applicationContext as io.todu.andjs.AndJsApplication).externalFlow=false
        status=fitness.status();denied=it.values.any {granted->!granted}
    }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,preview) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME && !preview) {status=fitness.status();writeSettings=Settings.System.canWrite(context)}}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    fun open(action:String) {
        if(preview) {message="请在手表上授权";return}
        try {context.startActivity(Intent(action,Uri.parse("package:${context.packageName}")))}
        catch(_:Exception) {message="此设备没有对应的授权页面"}
    }
    BackHandler(onBack=onBack)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp,vertical=32.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item {Text("权限授权",fontSize=16.sp)}
        item {ConnectionPermissions(preview)}
        for((label,key) in listOf("步数" to "steps","心率" to "heartRate")) item {
            Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(label,fontSize=13.sp)
                Text(when {preview->"真机授权";status[key]!=true->"不支持";status[key+"Granted"]==true->"已授权";else->"未授权"},fontSize=12.sp,color=Color.LightGray)
            }
        }
        item {Button(onClick={
            if(preview) message="请在手表上授权"
            else if(denied || fitness.permissions().isEmpty()) open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            else permissionFlow(context) {launcher.launch(fitness.permissions())}
        },modifier=Modifier.fillMaxWidth()) {Text("健身传感器",fontSize=13.sp)}}
        item {OutlinedButton(onClick={open(Settings.ACTION_MANAGE_WRITE_SETTINGS)},modifier=Modifier.fillMaxWidth()) {Text(if(writeSettings) "系统设置 · 已授权" else "修改系统设置",fontSize=12.sp)}}
        item {OutlinedButton(onClick={open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)},modifier=Modifier.fillMaxWidth()) {Text("应用权限",fontSize=13.sp)}}
        message?.let {item {Text(it,fontSize=12.sp,color=Color.LightGray)}}
        item {TextButton(onClick=onBack) {Text("返回")}}
    }
}
