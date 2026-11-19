package io.todu.andjs.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable fun LicenseScreen(onBack:()->Unit) {
    val context=LocalContext.current
    val text=remember {listOf("ZXing","Lucide","Feather","Rhino","Babel","OkHttp","Okio","AndroidX-Health-Guava").joinToString("\n\n") {name -> name+"\n\n"+context.assets.open("licenses/$name.txt").bufferedReader().use {it.readText()}}}
    BackHandler(onBack=onBack)
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={Row(Modifier.statusBarsPadding().height(60.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick=onBack){Glyph("back",MaterialTheme.colorScheme.onSurface,Modifier.size(22.dp),"返回")}
        Text("开源许可",fontSize=18.sp)
    }}) {padding->Text(text,fontSize=12.sp,modifier=Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp))}
}
