package io.todu.andjs.domain

object DeviceEventsApiCatalog {
    private const val lifecycle="手机、手表均使用本机硬件。脚本返回、exit、停止、关闭运行窗口或进入后台会释放资源；没有后台自启。回调由 listen() 在脚本线程串行处理，可以弹出输入或选择窗口。弹窗期间只保留最新事件；listen 阻塞后续语句，不能在回调中嵌套 listen。"
    val methods=listOf(
        ApiMethod("network.scanPorts",ApiCategory.NETWORK,"andjs.network","andjs.network.scanPorts(options)","扫描单个目标的 TCP 端口，连接后立即关闭，不发送数据。",listOf(
            ApiArgument("options.host","string","单个 IP 地址或域名，不含 URL、路径或端口。域名最多解析等待 5 秒，仅扫描返回的第一个地址；结果 address 为实际目标。"),
            ApiArgument("options.ports","number[]","1–65535 的端口整数，必填；手机最多 1024 项，手表 256 项，去重后保留输入顺序。不支持网段或端口范围字符串。"),
            ApiArgument("options.timeout","number，可选","单个端口连接超时，1–10000 毫秒整数，默认 800；不是总扫描超时。"),
            ApiArgument("options.concurrency","number，可选","并发数，手机 1–16、默认 8，手表 1–4、默认 4。应用内最多同时占用 16 个扫描名额，繁忙抛 BUSY。")
        ),"{host,address,items,openPorts,elapsedMs}。items 每项含 port、status、elapsedMs，失败时含 message。status：open 连接成功；closed 明确拒绝；timeout 超时；unreachable 无路由；error 其他错误。超时不代表端口关闭，open 也不保证具体协议可用。使用设备当前路由，不自动连接 Wi-Fi 或绕过 VPN；手机和手表独立运行。同步等待期间不阻塞界面；停止脚本、关闭运行窗口或进入后台取消未完成连接，不返回部分结果。参数错误抛 DeviceError/INVALID_OPTIONS，DNS_ERROR、DNS_TIMEOUT、BUSY、SCAN_ERROR 表示对应失败。仅扫描自己拥有或获准检测的设备。", "const result = andjs.network.scanPorts({host: \"192.168.1.1\", ports: [22, 53, 80, 443, 8080], timeout: 800, concurrency: 4});\nexit(result);","可用"),
        entry("sensors","watch(options)","监听实时传感器。type 支持 accelerometer、gyroscope、magnetic、gravity、linearAcceleration、rotation、light、pressure、proximity、temperature、humidity；以 status().types 为准。interval 为毫秒整数，手机 20–60000、默认 100；手表 100–60000、默认 200。系统实际采样频率可能更低。","const sensor = andjs.sensors.watch({type: \"accelerometer\", interval: 200});\nsensor.onMessage(function(event) { console.log(event.values); });\nsensor.listen();","事件含 type、values（Android 原生传感器单位）、accuracy、timestampNanos（开机后的单调时钟）。不包含心率等健康传感器。"),
        entry("network","watch()","监听默认网络变化，创建时提供一次当前快照；不扫描 Wi-Fi，不进行测速。","const network = andjs.network.watch();\nnetwork.onMessage(function(event) { console.log(event.type, event.connected); });\nnetwork.listen();","字段与 andjs.device.network() 相同，另含 event：initial / available / lost / changed。状态合并为最新快照，不能用于统计每次网络切换。"),
        entry("torch","open(options)","开启本机相机闪光灯，需要相机权限与闪光灯硬件。cameraId 可选；set(boolean) 切换开关，close() 关闭。设备被占用时抛错。","const light = andjs.torch.open();\ntry { andjs.ui.confirm(\"照明\", \"关闭手电筒？\"); } finally { light.close(); }","可调用 listen() 保持运行至用户停止，无需 onMessage。没有相机闪光灯的手表不支持；不以屏幕白光代替。"),
        entry("nfc","watch()","在应用前台监听本机 NFC 标签，需要 NFC 开启且系统提供 Reader Mode。标签中最多读取手机 16KB / 手表 4KB、32 条 NDEF 记录。","const reader = andjs.nfc.watch();\nreader.onMessage(function(tag) { console.log(tag.records); });\nreader.listen();","标签事件含 type=tag、id、technologies、ndef、writable、maxSize、records；记录含 tnf、Base64 type/payload，可解码时含 text 或 uri。读取失败可能返回 type=error；过大消息返回 error=MESSAGE_TOO_LARGE。有 NFC 支付功能不代表支持标签读写。"),
        entry("dnd","open(options)","临时开启勿扰，需要系统勿扰访问授权。mode 为 priority（默认）、alarms 或 none。Android 10+ 创建本应用临时规则，close() 删除；旧系统仅在设置仍为本次值时恢复原状态。","const quiet = andjs.dnd.open({mode: \"priority\"});\nquiet.listen();","listen() 无需 onMessage。规则与其他系统规则共同决定最终效果。正常停止、退出或后台会撤销；进程被强杀时系统规则可能保留，可在系统勿扰设置关闭，应用下次启动也会清理。")
    ) + listOf(
        ApiMethod("nfc.write",ApiCategory.DEVICE,"andjs.nfc","reader.write(id, text, options)","向最近发现的 NDEF 标签写入一条文本或 URI 记录，覆盖原 NDEF 消息。",listOf(ApiArgument("id","string","当前标签事件的 id。"),ApiArgument("text","string","文本或包含协议的 URI，UTF-8 最多手机 8192 / 手表 2048 字节，并受标签容量限制。"),ApiArgument("options","object，可选","type 为 text（默认）或 uri；timeout 为 1–10000 毫秒整数，默认 5000。")),"true；失败抛 DeviceError。只支持已格式化且可写标签；不提供格式化、锁定、支付卡或门禁模拟。超时或停止不能撤销已经写入的数据，须重新读取确认。","const reader = andjs.nfc.watch();\nreader.onMessage(function(tag) {\n  if (tag.type === \"tag\" && tag.writable) {\n    reader.write(tag.id, \"Hello\");\n    reader.close();\n  }\n});\nreader.listen();","可用"),
        ApiMethod("device.events.handle",ApiCategory.DEVICE,"设备监听","handle.onMessage / onError / onClose / receive / listen / info / close","五类设备接口返回的句柄共用生命周期。",emptyList(),"onMessage(fn)、onError(fn)、onClose(fn) 注册处理器并返回句柄；listen() 驱动回调直到关闭；receive(timeout) 拉取单个事件，1–15000ms、默认 10000，超时 null。不可与 listen 混用。info() 返回 kind/closed/code；close() 幂等。主动关闭在 listen 中触发 onClose；脚本停止不会再执行回调。每次脚本最多创建 64 个句柄，同时手机 12 / 手表 6 个；手电筒、NFC、勿扰各只允许一个操作。错误对象含 name=DeviceError、code、message；常见 code：UNSUPPORTED、PERMISSION_DENIED、DISABLED、CLOSED、BUSY、TIMEOUT、IO_ERROR。", "const network = andjs.network.watch();\ntry { console.log(network.receive(1000)); } finally { network.close(); }","可用")
    )
    private fun entry(namespace:String,signature:String,description:String,example:String,result:String)=ApiMethod("$namespace.events",ApiCategory.DEVICE,"andjs.$namespace","andjs.$namespace.$signature",description,when(namespace) {
        "sensors"->listOf(ApiArgument("options.type","string，可选","默认 accelerometer。支持类型见 sensors.status().types。"),ApiArgument("options.interval","number，可选","采样间隔毫秒整数；手机 20–60000、默认 100；手表 100–60000、默认 200。"))
        "torch"->listOf(ApiArgument("options.cameraId","string，可选","选择带闪光灯的摄像头 ID；省略时使用首个可用摄像头。"))
        "dnd"->listOf(ApiArgument("options.mode","string，可选","priority（默认）、alarms 或 none。"))
        else->emptyList()
    },"$result $lifecycle",example,"可用")
}
