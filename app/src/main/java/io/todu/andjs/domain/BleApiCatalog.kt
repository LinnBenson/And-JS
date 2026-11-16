package io.todu.andjs.domain

object BleApiCatalog {
    private fun field(name:String,type:String,text:String)=ApiArgument(name,type,text)
    private fun api(id:String,signature:String,description:String,args:List<ApiArgument>,result:String,example:String)=ApiMethod("ble.$id",ApiCategory.NETWORK,"andjs.ble",signature,description,args,result,example,"可用 · 依本机蓝牙硬件与权限支持")
    val methods=listOf(
        api("connect","andjs.ble.connect(address, options)","连接本机附近的 BLE 外设，自动发现 GATT 服务。",listOf(
            field("address","string","设备蓝牙地址，来自扫描结果。手机与手表均使用自己的蓝牙硬件，不依赖另一台设备。"),
            field("options.timeout","number","连接/发现服务及每次读写最长等待时间，1–15000 毫秒，默认 10000。超时会关闭连接，防止迟到结果混入后续操作。")
        ),"连接对象：services、read、send/write、subscribe、unsubscribe、mtu、receive、onMessage/onError/onClose、listen、info、close。不自动配对、不自动重连；设备要求加密或配对时需先通过系统设置处理。需要蓝牙连接权限。",
            "const device = andjs.ble.connect(\"AA:BB:CC:DD:EE:FF\");\nconsole.log(device.services());\ndevice.close();"),
        api("scan","andjs.ble.scan(options)","持续监听 BLE 广播，直到关闭或脚本结束。",listOf(
            field("options.address / service","string，可选","按地址或服务 UUID 过滤，可组合。建议设置过滤以减少无关广播。UUID 支持 4、8 位及标准完整格式。"),
            field("options.interval","number，可选","同一地址事件的最小间隔，0–60000 毫秒，默认 1000；仅对最多 128 个最近地址限频。期间重复事件会合并忽略。")
        ),"扫描对象，支持 onMessage/onError/onClose、receive、listen、info、close。消息包含 type=advertisement、address、name、rssi、serviceUuids，以及完整原始广播的 base64/hex/text/size。text 仅是 UTF-8 解码，不保证广播本身是文字。需要精确位置、系统定位和蓝牙扫描权限。",
            "const scan = andjs.ble.scan({service: \"180D\"});\nscan.onMessage(function (event) { console.log(event.address, event.hex); });\nscan.listen();"),
        api("advertise","andjs.ble.advertise(options)","发送无需连接的 BLE 广播。",listOf(
            field("options.service / manufacturerId","string / number","二选一：服务 UUID 或 0–65535 的厂商编号。发送的是 service data 或 manufacturer data。"),
            field("options.data / encoding","string","data 默认空；encoding 默认 hex，可用 hex、base64、text。传统广播总量最多 31 字节，需扣除协议与 UUID 等开销；超限由系统报错。")
        ),"广播对象，支持 listen、info、close、onError/onClose。listen 不需要 onMessage，可等待直到用户停止。广播本身不接收数据；接收别人的广播请用 scan。对象创建成功后即开始广播，脚本自然结束即停止。修改广播内容需 close 后重新创建；不提供 GATT 服务端。需要广播权限和硬件支持。",
            "const ad = andjs.ble.advertise({service: \"FFF0\", data: \"010203\"});\nad.listen();"),
        api("services","device.services()","列出远端 GATT 服务和特征值。",emptyList(),"数组，每项含 uuid 和 characteristics；特征值含 uuid、properties 原始位标志及 read/write/writeWithoutResponse/notify/indicate 布尔值。重复服务或特征 UUID 暂不支持按实例选择。","console.log(device.services());"),
        api("send","device.send(service, characteristic, data, options)","写入 GATT 特征值；write 为同义方法。",listOf(
            field("service / characteristic","string","服务与特征 UUID。需使用目标设备提供的协议与 UUID。"),
            field("data","string","发送的数据。"),field("options.encoding","string","text（默认）、hex 或 base64。"),
            field("options.withoutResponse","boolean","默认 false；true 使用无响应写入，需要特征支持。成功仅代表系统处理了写入，不代表对端业务已处理。")
        ),"true。一次最多 min(512, MTU−3) 字节，默认 MTU 23，即 20 字节。不自动分包；分包须按设备协议处理。每个连接串行执行 GATT 操作，失败抛 BleError。",
            "device.send(\"FFF0\", \"FFF1\", \"010203\", {encoding: \"hex\"});"),
        api("read","device.read(service, characteristic)","读取特征值。",listOf(field("service / characteristic","string","目标服务与特征 UUID，需支持读取。")),"{base64,hex,text,size}；二进制数据应使用 hex 或 base64，text 按 UTF-8 解码。","console.log(device.read(\"180F\", \"2A19\"));"),
        api("subscribe","device.subscribe(service, characteristic, indicate)","订阅特征值通知；与 onMessage 和 listen 配合持续接收。",listOf(field("service / characteristic","string","目标 UUID，特征必须含 CCCD 描述符。"),field("indicate","boolean，可选","默认优先 notify，无 notify 时使用 indicate。可明确指定 true 使用 indicate。")),"true 表示 CCCD 配置成功。消息包含 type=notification、address、service、characteristic、base64/hex/text/size。unsubscribe(service,characteristic) 取消订阅。",
            "device.onMessage(function (event) { console.log(event.hex); });\ndevice.subscribe(\"FFF0\", \"FFF2\");\ndevice.listen();"),
        api("mtu","device.mtu(size)","请求协商连接 MTU。",listOf(field("size","number","23–517 的整数。")),"返回系统报告的实际 MTU，不保证等于请求值；数据长度限制按实际 MTU 计算。","console.log(device.mtu(247));"),
        api("listen","handle.listen()","在当前脚本中持续等待并分发消息回调。",emptyList(),"连接或扫描需先 onMessage(fn)，广播可直接 listen。回调串行执行，可正常发送数据、请求网络及弹出输入、选择窗口。弹窗期间继续接收并排队；不会并行执行 JS。listen 期间不能对同一对象 receive，回调内不能嵌套 listen。脚本无总时限，但进入后台、停止、关闭运行窗口、退出应用或 exit 都会清理 BLE，不自动恢复。回调中 exit 结束整次运行。",
            "device.onMessage(function (event) {\n  console.log(event.hex);\n});\ndevice.onError(function (e) { console.log(e.code, e.message); });\ndevice.onClose(function (info) { console.log(info.code); });\ndevice.listen();"),
        api("receive","handle.receive(timeout)","手动读取一条消息，可替代回调监听。",listOf(field("timeout","number，可选","1–15000 毫秒，默认 10000。")),"消息对象；超时返回 null。手机队列最多 32 条且编码事件总量 128KB，手表 8 条且 32KB；溢出会清空并关闭，报 QUEUE_OVERFLOW。扫描事件原始数据最多 1650 字节，特征通知最多 512 字节。","const message = device.receive(10000);\nif (message) console.log(message.hex);"),
        api("close","handle.close()","停止此对象的扫描、广播或 GATT 连接。",emptyList(),"可重复调用；脚本结束也会自动清理。info() 返回 {kind,closed,code}。监听期间正常关闭最多通知一次 onClose；错误先 onError 再 onClose，未注册 onError 时 listen 抛错。同步方法错误仍由 try/catch 处理；停止或退出后不再执行任何回调。每次运行手机最多同时 6 个 BLE 对象、累计 32 个；手表同时 3 个、累计 16 个。",
            "device.close();"),
        api("onMessage","handle.onMessage(callback)","注册广播或特征通知回调。",listOf(field("callback","function","接收消息对象。重复注册替换原回调；注册本身不保持运行，需调用 listen。")),"返回当前对象。回调在脚本线程串行执行，可使用输入、选择窗口。","device.onMessage(function (event) { console.log(event.hex); });"),
        api("onError","handle.onError(callback)","注册监听期间的错误回调。",listOf(field("callback","function","参数为 BleError 字段对象。同步 connect/read/send 等错误仍直接抛出。")),"返回当前对象。未注册时 listen 抛错。","device.onError(function (error) { console.log(error.code); });"),
        api("onClose","handle.onClose(callback)","注册监听期间的关闭回调。",listOf(field("callback","function","接收 {kind,closed,code}，每个对象最多通知一次。")),"返回当前对象。脚本停止或退出时不再调用回调。","device.onClose(function (info) { console.log(info.code); });"),
        api("errors","BleError","BLE 错误字段与常见原因。",emptyList(),"name/code/message/status。常见 code：INVALID_OPTIONS、PERMISSION_DENIED、UNSUPPORTED、BLUETOOTH_OFF、LOCATION_OFF、RESOURCE_LIMIT、CONNECT_FAILED、DISCONNECTED、TIMEOUT、GATT_ERROR、GATT_BUSY、NOT_FOUND、SCAN_FAILED、ADVERTISE_FAILED、MESSAGE_TOO_LARGE、QUEUE_OVERFLOW、CLOSED。status 保留平台错误码。停止直接结束脚本，不再派发错误回调。",
            "try { device.read(\"180F\", \"2A19\"); } catch (e) { console.log(e.code, e.status, e.message); }")
    )
}
