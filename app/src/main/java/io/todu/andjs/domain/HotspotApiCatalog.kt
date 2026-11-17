package io.todu.andjs.domain

object HotspotApiCatalog {
    private const val availability="手机、手表 · 依系统版本和权限支持"
    val methods=listOf(
        ApiMethod("wifi.startHotspot",ApiCategory.NETWORK,"andjs.wifi","andjs.wifi.startHotspot(options)","创建热点，保持脚本运行，使用回调接收启动和停止结果。",listOf(
            ApiArgument("options.internet","boolean，可选","默认 false：仅局域网。true 请求系统 Wi-Fi 网络共享，需要 Android 16 及系统允许的网络共享权限；失败明确报错，不降级。"),
            ApiArgument("options.name / options.password","string，可选","省略时使用系统配置。自定义取决于运行时接口支持（公开设置接口从 36.1 提供）；名称 1–32 个 UTF-8 字节，密码 8–63 个 ASCII 字符。只传一项时另一项自动生成。"),
            ApiArgument("options.onStart","function(hotspot)，可选","成功后在脚本线程调用一次。hotspot 包含 requestId、name、password、status、internet、internetAvailable。系统未提供的数据为 null。"),
            ApiArgument("options.onStop","function(event)，可选","本次申请已释放或系统通知停止时调用一次；reason 为 released 或 system。released=true 表示已释放本次申请，deviceStopped=null 表示不能据此保证其他应用共用的热点已关闭。脚本清理期间限 250 毫秒，仅允许文字日志和普通 JS 运算。"),
            ApiArgument("options.onError","function(error)，可选","启动或运行失败，包含 code、message、fatal；通知后仍抛错。参数校验失败直接抛错。"),
            ApiArgument("options.onClientsChanged / options.onInternetChanged","function(event)，可选","预留回调；当前版本无法可靠获取连接人数与热点上游互联网验证结果，不发送推测事件。请查看 capabilities.events。")
        ),"同步等待启动最多 30 秒，返回 hotspot；代码执行完仍处理事件并保持运行。无需 await 或死循环。停止脚本、离开运行界面或脚本异常会释放申请。每台设备同时一个 And JS 热点请求；待取消的请求未清理前禁止重启。互联网模式启用共享不代表一定有互联网；internetAvailable=null 表示未知。热点启动后可顺序调用 HTTP 服务，不要在回调中启动服务。",
        """andjs.wifi.startHotspot({
  internet: false,
  onStart: function (hotspot) {
    console.log(hotspot.name);
    console.log(hotspot.password);
  },
  onStop: function (event) { console.log(event.reason); },
  onError: function (error) { console.log(error.code, error.message); }
});
// 可在这里继续调用 andjs.httpServer.upload({...}) 或 chat({...})。
""",availability),
        ApiMethod("wifi.getHotspotCapabilities",ApiCategory.NETWORK,"andjs.wifi","andjs.wifi.getHotspotCapabilities()","查询热点配置、权限和事件支持情况。",emptyList(),"wifi 表示硬件声明；localOnly=unknown，需启动验证。customConfiguration 为是否能构造自定义配置。permissionGranted 为局域网热点权限。internet 为 unsupported、permissionRequired 或 available，不保证固件能成功启动。deviceControl=bestEffort；events 列出各回调是否可观测。","console.log(andjs.wifi.getHotspotCapabilities());",availability),
        ApiMethod("wifi.getHotspotState",ApiCategory.NETWORK,"andjs.wifi","andjs.wifi.getHotspotState()","查询所有脚本的热点请求及设备热点状态。",emptyList(),"requests 是本进程全部申请，不含密码。device.status 为 starting、started、stopping、stopped、failed 或 unknown；unknown 表示系统拒绝或无法查询。externalHotspotPossible 表示设备报告运行但无本应用申请，不能据此判定为遗留。","console.log(andjs.wifi.getHotspotState());",availability),
        ApiMethod("wifi.stopHotspot",ApiCategory.NETWORK,"andjs.wifi","andjs.wifi.stopHotspot()","任意脚本全局停止热点，并尝试关闭设备上的 Wi-Fi 热点与互联网共享。",emptyList(),"取消所有执行的申请，释放本应用句柄，再尝试系统级关闭。返回 status、deviceStopped、device、requests、attempts。status 为 stopped、stopping、incomplete 或 unknown；deviceStopped=null 表示无法确认。系统级方法可能权限不足或不可用，各项如实返回，不影响本应用清理。可重复调用；不关闭蓝牙或 USB 共享。","console.log(andjs.wifi.stopHotspot());",availability),
        ApiMethod("wifi.checkHotspotLeaks",ApiCategory.NETWORK,"andjs.wifi","andjs.wifi.checkHotspotLeaks()","检查所有执行的待取消请求、未释放句柄和系统热点状态。",emptyList(),"只检查不修改。residuals 列出已取消或所属执行已停止却仍持有的资源；hasResiduals 只表示本进程已知遗留。complete=false 表示系统不能枚举所有应用的申请，不能当作全设备无遗留；deviceStateKnown 表示是否读到设备状态。系统热点来源无法完整枚举；其他应用共用热点不会误判为本应用遗留。","console.log(andjs.wifi.checkHotspotLeaks());",availability)
    )
}
