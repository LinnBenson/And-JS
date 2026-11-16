package io.todu.andjs.domain

/** Short usage guidance shared by the help screen and editor catalogue. */
object ApiUsage {
    fun explain(method:ApiMethod):ApiMethod {
        val id=method.id
        val notes=when {
            id.startsWith("http.")->listOf("将 example.com 替换为你的服务地址。同步等待返回结果，网络失败可用 try/catch 读取 e.code 和 e.message。","默认非 2xx 响应也会返回，请判断 response.ok；只有需要抛错时才设置 throwOnHttpError。")
            id.startsWith("ws.")->listOf("先用 andjs.ws.connect 创建 ws；示例地址须替换为实际 WebSocket 服务地址。","onMessage 只注册处理器，listen 才分发回调并保持运行。输入弹窗期间消息排队；停止脚本或进入后台会断开。")
            id.startsWith("ble.")->listOf("先在权限授权页授予蓝牙权限，扫描还需精确位置与系统定位开启。手机和手表各自使用本机蓝牙。","示例蓝牙地址和 UUID 仅展示写法，须按扫描结果及设备协议替换；注册回调后调用 listen 才会处理消息。")
            id.startsWith("files.")->listOf("app:/ 是本机应用文件目录，无需存储授权；shared:/ 是手机共享存储，需要授权，手表不支持。","读取、复制和移动前确认源文件存在。文本用 utf8，二进制用 base64 或 files.copy；文件不会自动同步。")
            id.startsWith("health.")->listOf("先调用 andjs.health.status() 检查本机能力，再到权限授权页授权。手机历史数据来自 Health Connect。","current 是一次实时采样；summary('steps') 才是今日步数。无数据可能为 null，不应当成 0。")
            id.startsWith("ui.")->if(id=="ui.close") listOf("调用后当前脚本及其子脚本立即停止，后续语句不再执行。") else listOf("调用会等待用户操作，界面仍可使用。先判断结果是否为 null，再处理输入或选择。","位置参数不能直接跳过；不需要说明时传空字符串，例如 select('标题', '', ['A', 'B'])。")
            id.startsWith("log.")->listOf("第二个参数是日志 ID；info、warn、error、img 共用，相同 ID 原位替换内容和类型。","省略 ID 或传 null 时追加。img 的宽度是第三个参数，例如 img(source, null, 160)。")
            id.startsWith("value.")->listOf("操作当前脚本的参数；参数名区分大小写。get 的默认值只在键不存在时生效。","set/delete 立即保存，脚本之后失败也不会回滚；修改 all() 返回的对象不会自动保存。")
            id.startsWith("global.")->listOf("全局变量由本机脚本共享，与同名脚本参数互不覆盖。get 的默认值只在键不存在时生效。","set/delete 立即保存；每次运行先读取快照，其他同时运行的脚本不会自动实时刷新。")
            id.startsWith("cache.")->listOf("缓存为本机脚本共享的空间，支持 JSON 值；ttl 的单位是秒。","重启可保留未过期缓存；清除缓存或手表收到同步会清空。长期数据请保存为文件或变量。")
            id.startsWith("notes.")->listOf("便签编号为 1–4。手机与手表读取本机内容，手机修改后需要同步；下次同步会覆盖手表便签。")
            id.startsWith("clipboard.")->listOf("读取或写入当前设备剪贴板。系统可能限制后台或悬浮窗口读取，读取为空时先判断 null。")
            id.startsWith("sensors.")->listOf("status().types 列出本机可用类型；watch 的 interval 单位为毫秒。","事件 values 使用 Android 原生单位，例如加速度 m/s²、角速度 rad/s、光照 lx、气压 hPa。弹窗期间保留最新采样。")
            id.startsWith("network.")->if(id=="network.scanPorts") listOf("host 填设备 IP 或域名，不填 Wi-Fi 名称、URL 或网段；ports 必须是端口数组。","只检测 TCP 连接，不识别服务协议；超时不等于端口关闭。停止或进入后台会取消扫描。") else listOf("读取或监听当前默认网络；不扫描附近 Wi-Fi，也不自动连接网络。","watch 返回监听对象，注册 onMessage 后调用 listen。事件合并为最新快照，不保证记录每次变化。")
            id.startsWith("torch.")->listOf("需要相机权限及真实闪光灯硬件，通常没有相机的手表不支持。","open 即开启，set(false) 暂时关闭，close 释放；脚本结束或进入后台会关闭闪光灯。")
            id.startsWith("nfc.")->listOf("需要设备开放 NFC 标签读写能力并开启 NFC。仅支持应用页面在前台使用；有支付功能不代表能读写标签。","write 会覆盖整个 NDEF 消息，只支持已格式化且可写标签；超时或停止不能撤销已写入的数据。")
            id.startsWith("dnd.")->listOf("先在权限授权页授予勿扰访问权限。mode 可选 priority、alarms、none。","正常结束会撤销临时勿扰；进程强杀可能留下规则，可在系统设置关闭，应用下次启动也会清理。")
            id.startsWith("device.handle.") || id.startsWith("device.events.")->listOf("handle 指 watch/open 返回的对象；示例使用网络监听对象。各对象共享接收和关闭方法。","listen 串行处理回调，不能在回调中嵌套 listen；弹窗期间保留最新设备事件，停止后不再执行回调。")
            id.startsWith("device.")->when(id) {
                "device.wifiList","device.location"->listOf("先授予精确位置权限并开启系统定位；Wi-Fi 扫描还需要系统允许扫描。","读取本机信息，不自动连接 Wi-Fi；手表独立使用自己的硬件。")
                "device.bluetoothList"->listOf("paired 读取已配对列表，classic/ble 扫描附近设备；配对不等于已连接。","扫描需要蓝牙和位置授权，蓝牙及系统定位开关也需开启。")
                else->listOf("读取当前设备的快照，不会持续监听。无法提供的字段可能为 null，请按返回字段判断。")
            }
            id.startsWith("system.")->when(id) {
                "system.setBrightness","system.setAutoBrightness","system.setScreenTimeout","system.setRotation"->listOf("先在权限授权页开启“修改系统设置”。这些设置会影响系统，脚本结束后不会自动恢复。")
                "system.openApp","system.openUrl","system.openSettings","system.share","system.openRecents","system.returnToWatchFace"->listOf("请放在脚本最后执行；离开应用进入后台后会停止当前脚本。成功返回只代表系统接受请求。")
                "system.closeApp"->listOf("会关闭整个 And JS 并结束进程，未保存的编辑会丢失，后续代码不会执行。")
                "system.notify","system.cancelNotification"->listOf("通知 ID 是数字，与日志 ID 无关；保存 notify 的返回值可更新或撤回对应通知。","发送前先授权通知。脚本结束不会自动撤回已发送通知。")
                "system.speak","system.playAudio"->listOf("调用等待播放完成，volume 为 0–1 的播放比例，不修改系统音量。停止脚本会停止播放。")
                "system.openNotificationShade"->listOf("需要在权限授权页开启系统操作无障碍服务；是否支持由设备系统决定。")
                "system.setVolume"->listOf("先读取 getVolume(stream).max，再设置该范围内的音量等级；不是百分比。")
                else->emptyList()
            }
            id=="script.run"->listOf("先创建并保存目标脚本，在编辑页点击脚本 ID 复制完整 ID；示例中的占位 ID 必须替换。","同步等待子脚本返回。日志与日志 ID 共享，子脚本中的普通 exit 只返回调用方；停止信号作用于整个调用链。")
            id=="permissions.status"->listOf("此接口只读取已列出的权限，不弹出授权窗口；位置和蓝牙状态另见对应 device 接口，其他权限见设置中的权限授权。")
            id=="util.date" || id=="util.time"->listOf("这两个接口使用秒级时间戳；JavaScript Date.now() 和健康接口使用毫秒，转换时注意除以 1000。")
            id=="exit"->listOf("exit(value) 显示最终结果并结束执行；console.log 和 andjs.log 只写日志，不会结束脚本。")
            else->emptyList()
        }
        var example=examples[id] ?: method.example
        if(id in listOf("http.request","http.get","http.post","http.put","http.patch","http.delete","http.head","http.options")) {
            val verb=id.substringAfter('.')
            val call=when(verb) {
                "request"->"andjs.http.request(url, {method: \"GET\", timeout: 10000})"
                "get"->"andjs.http.get(url, {query: {page: 1}, retry: 2})"
                "post","put","patch"->"andjs.http.$verb(url, {data: {name: \"And JS\"}})"
                else->"andjs.http.$verb(url)"
            }
            example="// 替换为你的接口地址；写请求请确认目标资源\nconst url = \"https://example.com/api/items\";\ntry {\n  const response = $call;\n  if (!response.ok) exit({status: response.status, text: response.text});\n  exit(response.${if(verb in listOf("head","options")) "headers" else "data"});\n} catch (error) {\n  exit({code: error.code, message: error.message});\n}"
        }

        if(id.startsWith("ws.") && !example.contains("andjs.ws.connect(")) {
            example="// 替换为你的 WebSocket 服务地址\nconst ws = andjs.ws.connect(\"wss://example.com/socket\");\n"+example
            if(!example.contains("ws.onMessage(")) example+="\nws.onMessage(function (message) { console.log(message.data); });"
            if(!example.contains("ws.listen(")) example+="\nws.listen();"
        }
        if(id.startsWith("ble.") && !example.contains("andjs.ble.")) {
            example="// 替换为目标设备地址，UUID 也须符合设备协议\nconst device = andjs.ble.connect(\"AA:BB:CC:DD:EE:FF\");\ntry {\n"+example.prependIndent("  ")+"\n} finally {\n  device.close();\n}"
            if(id in listOf("ble.onMessage","ble.onError","ble.onClose")) {
                val extra=(if(id!="ble.onMessage") "  device.onMessage(function (event) { console.log(event.hex); });\n" else "")+"  device.subscribe(\"FFF0\", \"FFF2\");\n  device.listen();\n"
                example=example.replace("} finally {",extra+"} finally {")
            }
        }
        val summary=when(id) {
            "sensors.events"->"持续读取本机传感器，通过回调接收最新采样。"
            "network.events"->"监听默认网络变化，接收当前连接状态。"
            "torch.events"->"开启本机闪光灯，脚本结束时自动关闭。"
            "nfc.events"->"在应用前台读取附近 NFC 标签。"
            "dnd.events"->"在本次脚本运行期间临时开启勿扰。"
            else->method.summary
        }
        return method.copy(summary=summary,example=example,usage=notes)
    }
    private val examples=mapOf(
        "value.get" to "const name = andjs.value.get(\"name\", \"朋友\");\nexit(\"你好，\" + name);",
        "global.get" to "const retries = andjs.global.get(\"retries\", 3);\nexit({retries: retries});",
        "ui.input" to "const name = andjs.ui.input(\"输入名称\", \"填写你的称呼\", \"朋友\");\nif (name === null) exit(\"已取消\");\nexit(\"你好，\" + name);",
        "ui.select" to "const options = [\"苹果\", \"香蕉\"];\nconst i = andjs.ui.select(\"选择水果\", \"点击一项即可确定\", options);\nif (i === null) exit(\"已取消\");\nexit(options[i]); // 下标从 0 开始",
        "ui.multiSelect" to "const options = [\"苹果\", \"香蕉\", \"橙子\"];\nconst selected = andjs.ui.multiSelect(\"选择水果\", \"可不选，也可多选\", options);\nif (selected === null) exit(\"已取消\");\nexit(selected.map(function (i) { return options[i]; }));",
        "ui.confirm" to "const confirmed = andjs.ui.confirm(\"继续执行？\", \"点击确定后才继续\");\nif (confirmed !== true) exit(\"已取消\");\nexit(\"已确认\");",
        "util.isJson" to "const text = '{\"name\":\"And JS\"}';\nif (andjs.util.isJson(text)) {\n  exit(JSON.parse(text).name);\n}\nexit(\"不是有效 JSON\");",
        "log.info" to "andjs.log.info(\"这条日志直接追加\");\nandjs.log.info(\"进度：0%\", \"progress\");\nandjs.log.info(\"进度：100%\", \"progress\"); // 更新原位置",
        "log.warn" to "andjs.log.info(\"正在连接\", \"status\");\nandjs.log.warn(\"连接较慢\", \"status\"); // 普通日志变为警告",
        "log.error" to "andjs.log.info(\"正在连接\", \"status\");\nandjs.log.error(\"连接失败\", \"status\"); // 同一条日志变为错误",
        "log.img" to "const image = andjs.util.qr(\"https://example.com\");\nandjs.log.info(\"生成二维码\", \"preview\");\nandjs.log.img(image, \"preview\", 160); // 替换文字日志\n// 不需要 ID、按容器宽度显示：\n// andjs.log.img(image, null, \"100%\");",
        "system.setVolume" to "const volume = andjs.system.getVolume(\"music\");\nandjs.system.setVolume(Math.round(volume.max / 2), \"music\");\nexit(andjs.system.getVolume(\"music\"));",
        "system.cancelNotification" to "const id = andjs.system.notify(\"示例通知\", \"两秒后撤回\");\nandjs.sleep(2000);\nandjs.system.cancelNotification(id);",
        "system.notify" to "const id = andjs.system.notify(\"下载\", \"准备开始\");\nandjs.system.notify(\"下载\", \"已完成\", id); // 更新同一通知\nexit({notificationId: id});",
        "health.current" to "const reading = andjs.health.current(\"heartRate\");\nif (reading === null) exit(\"暂未取得读数\");\nexit(reading.value + \" \" + reading.unit);",
        "health.summary" to "const today = andjs.health.summary(\"steps\");\nexit(today.value === null ? \"暂无步数数据\" : today.value);",
        "device.location" to "const location = andjs.device.location({timeout: 10000, maxAge: 60000});\nif (location === null) exit(\"定位超时\");\nexit({latitude: location.latitude, longitude: location.longitude, accuracy: location.accuracy});",
        "files.read" to "const path = \"app:/help-example.txt\";\nif (!andjs.files.exists(path)) {\n  andjs.files.write(path, \"你好，And JS\");\n}\nexit(andjs.files.read(path));",
        "files.copy" to "andjs.files.write(\"app:/help-example.txt\", \"复制示例\");\nandjs.files.copy(\"app:/help-example.txt\", \"app:/help-copy.txt\", true);\nexit(andjs.files.read(\"app:/help-copy.txt\"));",
        "files.move" to "andjs.files.write(\"app:/help-example.txt\", \"移动示例\");\nandjs.files.move(\"app:/help-example.txt\", \"app:/help-moved.txt\", true);\nexit(andjs.files.exists(\"app:/help-moved.txt\"));",
        "files.delete" to "const path = \"app:/help-delete-example.txt\";\nandjs.files.write(path, \"仅删除这个示例文件\");\nexit(andjs.files.delete(path));",
        "ble.receive" to "const scan = andjs.ble.scan({service: \"180D\"});\ntry {\n  const event = scan.receive(10000);\n  exit(event === null ? \"等待超时\" : event);\n} finally {\n  scan.close();\n}",
        "ble.listen" to "const scan = andjs.ble.scan({service: \"180D\"});\nscan.onMessage(function (event) { console.log(event.address, event.hex); });\nscan.onError(function (error) { console.log(error.code, error.message); });\nscan.onClose(function (info) { console.log(info.code); });\nscan.listen();"
    )
}
