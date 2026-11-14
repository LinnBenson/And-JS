package io.todu.andjs.domain

object NotificationApiCatalog {
    val methods=listOf(
        ApiMethod("system.openRecents",ApiCategory.DEVICE,"andjs.system","andjs.system.openRecents()","打开或切换最近任务界面。",emptyList(),"boolean：系统是否成功执行。需开启系统操作无障碍服务，未授权时抛错；设备不支持时返回 false。进入后台后脚本按退出规则停止。","andjs.system.openRecents();","可用",devices="手机、手表 · 依系统支持"),
        ApiMethod("system.openNotificationShade",ApiCategory.DEVICE,"andjs.system","andjs.system.openNotificationShade()","展开系统通知栏。",emptyList(),"boolean：系统是否成功执行。需开启系统操作无障碍服务，未授权时抛错；设备不支持时返回 false。","andjs.system.openNotificationShade();","可用",devices="手机、手表 · 依系统支持"),
        ApiMethod("system.toast",ApiCategory.DEVICE,"andjs.system","andjs.system.toast(text, duration)","显示系统 Toast 提示。",listOf(ApiArgument("text","string","提示内容，1–2000 字符，实际显示行数由系统决定。"),ApiArgument("duration","string，可选","short 或 long，默认 short；显示时长由系统控制。")),"true：提示已提交显示，不等待提示消失。无需通知授权。","andjs.system.toast(\"任务完成\");","可用",devices="手机、手表"),
        ApiMethod("system.returnToWatchFace",ApiCategory.DEVICE,"andjs.system","andjs.system.returnToWatchFace()","返回手表表盘。",emptyList(),"true：已发出返回请求。应用退到后台后按退出规则停止脚本并释放资源；请作为脚本最后一步调用。手机及手机手表预览不支持，调用时抛错。","andjs.system.returnToWatchFace();","可用",devices="仅手表"),
        ApiMethod("system.notify",ApiCategory.DEVICE,"andjs.system","andjs.system.notify(title, content, id)","发送系统通知。",listOf(
            ApiArgument("title","string","必填，1–200 字符。"),
            ApiArgument("content","string，可选","正文，最多 4000 字符，默认空字符串。"),
            ApiArgument("id","number，可选","正整数；相同 id 更新已有通知，省略时自动生成。")
        ),"number：通知 id。需先授权通知，系统或通知渠道关闭时抛错。提示音和震动由系统通知设置控制；点击通知打开应用。",
            "const id = andjs.system.notify(\"提醒\", \"任务已完成\");","可用",devices="手机、手表 · 依系统通知支持"),
        ApiMethod("system.cancelNotification",ApiCategory.DEVICE,"andjs.system","andjs.system.cancelNotification(id)","撤回脚本发送的通知。",listOf(ApiArgument("id","number","发送通知返回的正整数 id。")),"true；通知不存在时也返回 true。","andjs.system.cancelNotification(10001);","可用",devices="手机、手表 · 依系统通知支持")
    )
}
