package io.todu.andjs.domain

object NotificationApiCatalog {
    val methods=listOf(
        ApiMethod("system.closeApp",ApiCategory.DEVICE,"andjs.system","andjs.system.closeApp()","关闭 And JS 并结束当前应用进程。",emptyList(),"无返回值：关闭应用弹窗和悬浮窗口，移除应用任务并结束当前进程。所有正在运行的脚本随之停止，调用后的代码不再执行。已保存的数据保留，未保存的编辑会丢失；不会撤销已发出的通知，也不会禁用小部件或系统服务，系统仍可能按需重新启动应用进程。手机手表预览中调用也会关闭手机上的 And JS。","andjs.system.closeApp();","可用",devices="手机、手表"),
        ApiMethod("system.speak",ApiCategory.DEVICE,"andjs.system","andjs.system.speak(text, volume)","使用系统语音引擎朗读文字。",listOf(ApiArgument("text","string","非空文字，最长为系统 TTS 支持的长度，通常为 4000 字符。使用引擎默认语言、语速和音调。"),ApiArgument("volume","number，可选","0–1，默认 1；相对于当前媒体音量的播放比例，0 静音。不会修改系统音量。")),"boolean：朗读完成返回 true；参数无效、初始化或朗读失败、超过 5 分钟时记录错误日志并返回 false，继续执行后续代码。同步等待，不阻塞界面；停止脚本会停止朗读并释放引擎。需设备安装语音引擎及语言包，离线支持取决于引擎。","andjs.system.speak(\"你好，And JS\", 0.8);","可用",devices="手机、手表 · 需系统语音引擎"),
        ApiMethod("system.playAudio",ApiCategory.DEVICE,"andjs.system","andjs.system.playAudio(path, volume)","播放本地音频文件。",listOf(ApiArgument("path","string","app:/ 下的文件路径（手机、手表通用），或已授权的 shared:/ 路径（手机）。相对路径按 app:/ 处理。支持系统可解码的音频，如 MP3、WAV、AAC；不接受网络 URL，需先下载并保存至 app:/。"),ApiArgument("volume","number，可选","0–1，默认 1；相对于当前媒体音量的播放比例，0 静音。不修改系统音量。")),"boolean：播放结束返回 true；文件不存在、格式不支持或超过 5 分钟时抛错。同步等待，不阻塞界面；停止脚本会停止播放并释放播放器。","andjs.system.playAudio(\"app:/sound.mp3\");","可用",devices="手机、手表 · 依音频格式支持"),
        ApiMethod("system.openRecents",ApiCategory.DEVICE,"andjs.system","andjs.system.openRecents()","打开或切换最近任务界面。",emptyList(),"boolean：系统是否成功执行。需开启系统操作无障碍服务，未授权时抛错；设备不支持时返回 false。进入后台后脚本按退出规则停止。","andjs.system.openRecents();","可用",devices="手机、手表 · 依系统支持"),
        ApiMethod("system.openNotificationShade",ApiCategory.DEVICE,"andjs.system","andjs.system.openNotificationShade()","展开系统通知栏。",emptyList(),"boolean：系统是否成功执行。需开启系统操作无障碍服务，未授权时抛错；设备不支持时返回 false。","andjs.system.openNotificationShade();","可用",devices="手机、手表 · 依系统支持"),
        ApiMethod("system.toast",ApiCategory.DEVICE,"andjs.system","andjs.system.toast(text, duration)","显示系统 Toast 提示。",listOf(ApiArgument("text","string","提示内容，1–2000 字符，实际显示行数由系统决定。"),ApiArgument("duration","string，可选","short 或 long，默认 short；显示时长由系统控制。")),"true：提示已提交显示，不等待提示消失。无需通知授权。","andjs.system.toast(\"任务完成\");","可用",devices="手机、手表"),
        ApiMethod("system.returnToWatchFace",ApiCategory.DEVICE,"andjs.system","andjs.system.returnToWatchFace()","返回手表表盘。",emptyList(),"true：已发出返回请求。应用退到后台后按退出规则停止脚本并释放资源；请作为脚本最后一步调用。手机及手机手表预览不支持；调用失败时记录错误日志并返回 false，继续执行后续代码。","andjs.system.returnToWatchFace();","可用",devices="仅手表"),
        ApiMethod("system.notify",ApiCategory.DEVICE,"andjs.system","andjs.system.notify(title, content, id)","发送系统通知。",listOf(
            ApiArgument("title","string","必填，1–200 字符。"),
            ApiArgument("content","string，可选","正文，最多 4000 字符，默认空字符串。"),
            ApiArgument("id","number，可选","正整数；相同 id 更新已有通知，省略时自动生成。")
        ),"number：通知 id。需先授权通知，系统或通知渠道关闭时抛错。提示音和震动由系统通知设置控制；点击通知打开应用。",
            "const id = andjs.system.notify(\"提醒\", \"任务已完成\");","可用",devices="手机、手表 · 依系统通知支持"),
        ApiMethod("system.cancelNotification",ApiCategory.DEVICE,"andjs.system","andjs.system.cancelNotification(id)","撤回脚本发送的通知。",listOf(ApiArgument("id","number","发送通知返回的正整数 id。")),"true；通知不存在时也返回 true。","andjs.system.cancelNotification(10001);","可用",devices="手机、手表 · 依系统通知支持")
    )
}
