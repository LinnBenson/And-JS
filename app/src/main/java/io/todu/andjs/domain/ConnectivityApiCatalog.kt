package io.todu.andjs.domain

object ConnectivityApiCatalog {
    val methods=listOf(
ApiMethod("device.network",ApiCategory.DEVICE,"andjs.device","andjs.device.network()","读取当前默认网络快照，不测速、不建立后台监听。",listOf(),"object。","exit(andjs.device.network());","可用",listOf(ApiArgument("connected","boolean","是否存在默认网络。"),ApiArgument("validated","boolean","系统是否验证互联网可用，状态可能随时变化。"),ApiArgument("metered","boolean","是否按流量计费。"),ApiArgument("type","string","none / wifi / cellular / ethernet / vpn / bluetooth / other。VPN 优先。"),ApiArgument("transports","string[]","当前网络传输类型，可同时含 VPN 和底层类型。"),ApiArgument("internet","boolean","网络声明可访问互联网，不代表验证成功。"),ApiArgument("captivePortal","boolean","系统是否检测到需要登录的门户。"),ApiArgument("vpn","boolean","默认网络是否包含 VPN。"),ApiArgument("downstreamKbps","number | null","系统报告下行带宽，Kbps；不是实时测速，无数据为 null。"),ApiArgument("upstreamKbps","number | null","系统报告上行带宽，Kbps；不是实时测速。"),ApiArgument("interfaceName","string | null","接口名称。"),ApiArgument("dns","string[]","默认网络 DNS 地址。"),ApiArgument("addresses","object[]","默认网络接口的本地地址，不是公网出口地址。"),ApiArgument("addresses[].address","string | null","IPv4/IPv6 地址。"),ApiArgument("addresses[].prefixLength","number","网络前缀长度。")),"手机、手表 · 依设备与权限支持"),
ApiMethod("device.wifi",ApiCategory.DEVICE,"andjs.device","andjs.device.wifi()","读取本机 Wi-Fi 状态，不扫描周边网络。",listOf(),"object。","exit(andjs.device.wifi());","可用",listOf(ApiArgument("available","boolean","硬件是否支持 Wi-Fi。"),ApiArgument("enabled","boolean","Wi-Fi 是否开启。"),ApiArgument("ssid","string | null","连接网络名称；需要精确位置授权且系统定位开启，未连接或被系统隐藏为 null。"),ApiArgument("bssid","string | null","接入点地址，系统限制时为 null。"),ApiArgument("rssi","number | null","信号强度 dBm。"),ApiArgument("frequencyMhz","number | null","频率 MHz。"),ApiArgument("linkSpeedMbps","number | null","链路协商速率 Mbps，不是实际下载速度。")),"手机、手表 · 依设备与权限支持"),
ApiMethod("device.wifiList",ApiCategory.DEVICE,"andjs.device","andjs.device.wifiList(options)","获取附近 Wi-Fi 列表，可刷新或读取系统缓存。",listOf(
    ApiArgument("options.refresh","boolean，可选","默认 true，发起扫描；false 只读取缓存。扫描需要精确位置授权、系统定位及 Wi-Fi 或 Wi-Fi 扫描开启。"),
    ApiArgument("options.timeout","number，可选","等待扫描最长时间，1–15000 毫秒整数，默认 10000。"),
    ApiArgument("options.maxItems","number，可选","手机默认及最多 100 项，手表 40 项。按信号强度降序，按接入点地址和频率去重。")
),"{items,status,refreshed,truncated,elapsedMs}。刷新被拒绝、失败或超时仍返回系统缓存，需检查 status 和每项 ageMs。空数组表示没有可用结果；无硬件、未授权或开关关闭抛错。系统扫描限流不可绕过，不提供 Wi-Fi 密码或连接功能。",
    "const result = andjs.device.wifiList({timeout: 10000});\nconsole.log(result.status);\nexit(result.items);","可用",listOf(
    ApiArgument("items","object[]","每项包含 ssid（隐藏网络可能为空）、bssid、rssi（dBm）、frequencyMhz、capabilities（系统报告的安全能力）、ageMs（结果年龄，毫秒；未知为 null）。"),
    ApiArgument("status","string","updated：收到系统成功扫描通知；cached：未刷新；rejected：系统拒绝启动，可能被限流；failed：系统报告失败；timeout：等待超时。扫描通知也可能来自系统或其他应用。"),
    ApiArgument("refreshed / truncated","boolean","是否收到成功更新通知 / 结果是否超过 maxItems 被截断。"),
    ApiArgument("elapsedMs","number","本次调用耗时，毫秒。脚本总运行时长不限；停止脚本会移除监听，系统 Wi-Fi 扫描本身无法取消。")
),"手机、手表 · 依硬件、权限与系统限制"),
ApiMethod("device.bluetoothList",ApiCategory.DEVICE,"andjs.device","andjs.device.bluetoothList(options)","读取已配对设备，或扫描附近经典蓝牙、BLE 设备。",listOf(
    ApiArgument("options.mode","string，可选","paired（默认）读取已配对列表；classic 扫描处于可发现状态的经典蓝牙设备；ble 扫描正在广播的 BLE 设备。已配对不代表已连接，两种扫描也不会发现所有蓝牙设备。"),
    ApiArgument("options.timeout","number，可选","1–15000 毫秒整数，默认 10000。classic 为最长等待时间；ble 为扫描窗口，窗口结束返回已发现设备；paired 不等待。"),
    ApiArgument("options.maxItems","number，可选","手机默认及最多 100 项，手表 40 项。按地址去重；扫描结果按信号强度降序。达到上限后只更新已收录设备。")
),"{items,mode,completed,timedOut,truncated,elapsedMs}。需蓝牙开启；Android 12+ 需蓝牙连接授权，扫描另需扫描授权。扫描还需精确位置与系统定位开启。无硬件、权限不足或扫描失败抛错，不会用空列表掩盖这些错误。停止或关闭运行窗口会取消本次蓝牙扫描，不自动配对或连接。",
    "const paired = andjs.device.bluetoothList();\nconsole.log(paired.items);\nconst nearby = andjs.device.bluetoothList({mode: \"ble\", timeout: 10000});\nexit(nearby.items);","可用",listOf(
    ApiArgument("items","object[]","每项包含 name（可能为 null）、address、type（classic/ble/dual/unknown）、bonded、rssi（dBm；配对列表为 null）。BLE 地址可能变化，不能当作永久身份。"),
    ApiArgument("completed / timedOut","boolean","classic 收到扫描结束通知为 completed=true；超时返回部分结果且 timedOut=true。ble 完成扫描窗口及 paired 读取完成时 completed=true、timedOut=false。"),
    ApiArgument("truncated / elapsedMs","boolean / number","是否因 maxItems 截断 / 调用耗时毫秒。手机、手表均只进行本机扫描；脚本总运行时长不限。")
),"手机、手表 · 依硬件、权限与系统限制"),
ApiMethod("device.bluetooth",ApiCategory.DEVICE,"andjs.device","andjs.device.bluetooth()","读取蓝牙状态，不扫描、不连接其他设备。",listOf(),"object。","exit(andjs.device.bluetooth());","可用",listOf(ApiArgument("available","boolean","是否存在蓝牙适配器。"),ApiArgument("granted","boolean","是否具备读取授权；Android 12+ 需蓝牙连接权限。"),ApiArgument("enabled","boolean | null","是否开启；未授权或无硬件为 null。"),ApiArgument("name","string | null","本机蓝牙名称；受限制时为 null。")),"手机、手表 · 依设备与权限支持"),
ApiMethod("device.locationStatus",ApiCategory.DEVICE,"andjs.device","andjs.device.locationStatus()","读取位置权限与系统定位开关。",listOf(),"object。","exit(andjs.device.locationStatus());","可用",listOf(ApiArgument("coarse","boolean","是否允许大致位置。"),ApiArgument("fine","boolean","是否允许精确位置。"),ApiArgument("enabled","boolean","GPS 或网络定位是否开启；不保证当前能取得位置。")),"手机、手表 · 依设备与权限支持"),
ApiMethod("device.location",ApiCategory.DEVICE,"andjs.device","andjs.device.location(options)","获取一次本机位置，取得结果、超时或停止脚本后移除监听。",listOf(ApiArgument("options","object，可选","timeout 为 1–15000 毫秒整数，默认 10000；maxAge 为 0–86400000 毫秒整数，默认 60000，0 强制等待新位置。"),ApiArgument("options.timeout","number，可选","最长等待时间，毫秒。"),ApiArgument("options.maxAge","number，可选","允许的缓存年龄，毫秒。")),"object | null；超时无有效位置为 null。未授权或定位关闭抛错。仅前台读取，不申请后台定位，不依赖手机转发；手表需自身可用定位源。","exit(andjs.device.location({timeout: 10000, maxAge: 60000}));","可用",listOf(ApiArgument("latitude","number","WGS84 纬度。"),ApiArgument("longitude","number","WGS84 经度。"),ApiArgument("accuracy","number | null","水平误差半径，米。"),ApiArgument("altitude","number | null","高度，米；传感器未提供则 null。"),ApiArgument("speed","number | null","速度，米/秒。"),ApiArgument("bearing","number | null","行进方向角度，0 为北。"),ApiArgument("time","number","定位时间 Unix 毫秒。"),ApiArgument("provider","string | null","数据来源，例如 gps / network。"),ApiArgument("cached","boolean","是否使用满足 maxAge 的缓存。"),ApiArgument("mock","boolean","系统标记是否为模拟位置。")),"手机、手表 · 依设备与权限支持")
    )
}
