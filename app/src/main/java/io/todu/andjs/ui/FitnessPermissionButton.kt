package io.todu.andjs.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.todu.andjs.runtime.FitnessApi

@Composable fun FitnessPermissionButton() {
    val context=LocalContext.current
    val api=remember(context) {FitnessApi(context)}
    var status by remember {mutableStateOf(api.status())}
    var denied by remember {mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {result->
        (context.applicationContext as io.todu.andjs.AndJsApplication).externalFlow=false
        status=api.status();denied=result.values.any {!it}
    }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) status=api.status()}
        owner.lifecycle.addObserver(observer);onDispose {owner.lifecycle.removeObserver(observer)}
    }
    val missing=(status["steps"]==true && status["stepsGranted"]!=true) || (status["heartRate"]==true && status["heartRateGranted"]!=true)
    if(missing) TextButton(onClick={
        if(denied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))
        else permissionFlow(context) {launcher.launch(api.permissions())}
    }) {Text(if(denied) "健身权限设置" else "授权健身传感器")}
}
