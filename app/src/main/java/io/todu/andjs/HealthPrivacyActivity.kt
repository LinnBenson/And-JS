package io.todu.andjs
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.todu.andjs.ui.theme.AndJsTheme
class HealthPrivacyActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {AndJsTheme {Surface {Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("健康数据",style=MaterialTheme.typography.titleLarge)
            Text("And JS 仅在你运行脚本时，按已授予的权限读取 Health Connect 中的步数、心率、睡眠、距离、消耗热量和体重，不写入健康记录。")
            Text("健康数据会作为脚本返回值提供。脚本可以显示、保存或通过网络发送这些数据，请仅运行你信任的脚本。应用不会自行上传健康数据。")
            Text("你可以随时在 Health Connect 中撤销权限。脚本已保存到文件或变量的数据需要自行删除；同步全局变量时，其中由脚本保存的数据也会发送到手表。")
            Button(onClick={finish()}) {Text("返回")}
        }}}}
    }
}
