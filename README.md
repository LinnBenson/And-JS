# And JS

一个条目，一份 JavaScript。And JS 是面向 Android 的脚本快捷入口。

- 创建、编辑和删除脚本，按名称搜索，自定义分类，长按拖动排序。
- 102 枚分类图标，可搜索并为每个条目选择颜色。
- 首页可导入 JS 文件直接创建快捷方式，默认使用文件名。
- 编辑页提供 JS 文件导入、导出；导入替换当前代码，可撤销，保存后生效。文件使用 UTF-8 编码。
- 独立代码编辑页，支持行号、基础语法高亮、自动缩进、撤销/重做和符号快捷栏。
- 脚本参数和全局变量支持 boolean、string、number、array，以可搜索的单行列表管理，通过独立页面编辑，保存在本机。
- 设置中的接口声明按用途分类，提供搜索、参数说明和示例。
- 薄荷绿与青柠绿主题，深浅色跟随系统。

使用 Rhino 1.9.1 在后台执行 JavaScript，支持运行结果、日志、错误行号、停止和 15 秒执行上限。不预装示例脚本，可新建或导入 JS 文件。

内置方法见设置 → 接口声明：`andjs.value.get`、`andjs.global.get`、`exit(value)`、`console.log`、`andjs.log.info/warn/error`、`andjs.sleep`、`andjs.util.uuid/randomInt`。参数和全局变量在启动时读取快照，脚本自身通过 set/delete 写入后可立即读取更新。

运行环境不是浏览器或 Node.js，不提供 DOM、fetch、require、定时器或 Android Java 对象。当前以同步脚本为主，不提供异步任务调度。停止和超时通过解释器指令检查实现；个别原生运算可能延迟响应。没有独立进程或硬内存配额，请只运行可信脚本。手表提供独立的精简运行界面。

## 安装

支持 Android 8.0 及以上。按设备支持的架构选择 arm64-v8a 或 armeabi-v7a APK。发布压缩包包含两个正式签名 APK 及 SHA256SUMS.txt。

## 构建

使用 JDK 17、Android SDK 36：

```sh
./gradlew assembleRelease
```

默认输出未签名 APK。`scripts/package-release.py` 从仓库外的 `../key/` 读取本地签名资料，将签名 APK、校验文件和压缩包输出至 `../package/`。签名资料不得加入源码仓库。

架构说明见 [基础架构](docs/architecture/FOUNDATION.md)。图标使用 [Lucide](https://lucide.dev/)，来源、修改和许可见 [第三方说明](docs/third-party/LUCIDE.md)。

JavaScript 引擎：[Mozilla Rhino](https://github.com/mozilla/rhino)，MPL-2.0，未修改引擎源码。许可随应用提供。

`exit(value)` 立即终止脚本并输出结果；未调用或调用 `exit()` 时无返回内容。旧的 `andjs.result` 已移除，自定义脚本请改用 `exit`。

`exit(true)` 显示“执行成功”，`exit(false)` 显示“执行失败”；字符串及对象内的布尔值保持原样。

界面交互：`andjs.ui.input`、`select`、`multiSelect`、`confirm` 支持输入、单选、多选和确认。等待用户操作不计入 15 秒执行上限；交互窗口可取消；单选菜单点击选项立即提交。

交互说明作为第二个参数传入，例如 `andjs.ui.input("称呼", "请输入希望显示的称呼", "朋友")`、`andjs.ui.confirm("继续？", "确认后执行下一步")`。单选和多选为 `(title, content, options)`。不传说明时保持原布局。

脚本可用 `andjs.value.set/delete` 修改自己的参数，用 `andjs.global.set/delete` 修改全局变量。`set` 自动新增或更新，支持四种已有类型；每次调用立即保存，同次运行的 `get` 可读取更新，脚本出错或停止不回滚已保存修改。

实用工具：`andjs.util.isJson(value)` 检查合法 JSON 文本，`andjs.util.isUuid(value)` 检查标准 UUID 字符串，`andjs.util.md5(text)` 返回 UTF-8 字符串的 32 位小写 MD5。

## 网络、文件与系统

`andjs.http` 提供同步请求、上传和下载，支持查询参数、请求头、JSON、表单、文本、二进制、multipart、认证、Cookie、重定向及超时。请求在后台等待，不阻塞界面，可停止。默认使用 HTTPS，明文 HTTP 受 Android 系统策略限制。

```js
const response = andjs.http.post("https://example.com/api", {
   data: {name: "And JS", tags: ["phone", "watch"]}
});
exit(response.data);
```

`data` 为对象或数组时自动发送 JSON；响应默认自动解析 JSON，非 JSON 保持字符串。`response.text` 保留原文。可指定 `responseType: "text"`、`"json"`、`"base64"` 或 `"file"`。网络等待不计入脚本执行时限，但受请求超时限制。

设置中的“权限授权”可打开系统授权页。`andjs.device` 读取设备、电池和网络信息；`andjs.system` 读取或设置亮度、音量，也可打开手机设置。权限和硬件限制可能使调用失败。

`andjs.files` 支持读写、追加、列目录、复制、移动和删除。`app:/` 是应用专用文件目录，无需额外权限；`shared:/` 为手机共享存储，需要用户授权。网络下载文件位于 `app:/network/`。

接口声明列出返回对象的字段名称、类型和含义，并标识通用、仅手机及依设备支持的接口。手表布局与硬件行为仍需在目标设备验证；共享存储和手机设置入口在手表上禁用。

网络组件使用 OkHttp 4.12.0 与 Okio 3.6.0，Apache-2.0，许可随应用提供。

## 手表

手机底部“手表”栏目选择脚本，长按拖动排序，移除仅影响手表列表。添加、修改已选脚本、调整顺序或修改全局变量后，顶部出现同步图标。点击后发送完整快照，收到手表保存确认才清除待同步标记；失败可重试。没有点同步的修改不会发到手表。

手机和 Wear OS 手表需安装同包名、同正式签名的 And JS，通过 Google Play 服务 Data Layer 同步。目前一次同步一块已连接手表。同步包含脚本源码、名称、图标、颜色、脚本参数和全局变量，不包含脚本文件目录。

手机“手表”页可编辑四条便签，每条最多 4000 字符，随手动同步一起发送。手表顶部的 1–4 可打开便签并自动复制到手表本机剪贴板；空便签置灰。手机与手表均需更新至 1.1.17 或更高版本。

脚本可用 `andjs.notes.get(1)` 读取便签，用 `andjs.notes.set(1, "内容")` 修改；编号为 1–4，每条最多 4000 字符，传入空字符串可清空。保存成功返回 `true`，参数错误或保存失败时抛错。手机修改后需手动同步到手表；手表修改仅在本机保存，下次手机同步会覆盖。预览修改与手机隔离，刷新预览会重新加载手机便签。接口需 1.1.21 或更高版本。

手表保存后可独立运行，不需要手机在线。文件 API 的 app:/ 指向手表本地目录，网络 API 使用手表可用网络。手表运行时写入的变量仅保存在手表，下一次手机同步会覆盖这些变量；不会回传手机。手机专属 API 无法在手表运行。

手表使用黑色背景，默认三列宫格，可切换为列表并记住选择；提供运行与结果页，保留输入、单选、多选和确认，不提供分类、源码查看或编辑。同步最多 100 项、总计 2MB，每份源码最多 256000 字符；日志保留 40 条，结果显示最多 8000 字符。内存网络响应最多 2MB、文件下载最多 16MB，普通文件读取最多 2MB。手表网络请求单次最多 15 秒，连续运行另设 60 秒检查预算（不计用户交互等待）。JS 解释器仍在应用进程内，资源检查不能替代硬内存隔离。

进入后台会停止脚本、取消网络和等待中的交互，清除运行显示。手表立即退出任务，手机 15 秒后退出；文件选择、导出和系统授权流程例外。系统仍可能缓存空闲进程，由 Android 回收；桌面悬浮运行仅在弹窗显示期间保留前台服务，关闭弹窗或锁屏即结束；完成后无人操作的弹窗在 5 分钟后关闭。没有后台循环运行脚本。离开编辑页前请保存修改。

## 健康数据

手机在设置 → 权限授权中连接 Health Connect。数据需要由 Samsung Health 等健康应用写入 Health Connect；应用不会直接读取其他应用的私有数据库或模拟实时数据。

- `andjs.health.status()`：可用状态和已授权数据类型。
- `andjs.health.summary("steps" | "distance" | "calories", options)`：时间范围内的聚合值，避免多来源重复相加。
- `andjs.health.read("heartRate" | "sleep" | "weight", options)`：分页读取记录，返回字段说明见接口声明。

options.start/end 为 Unix 毫秒，默认今天零点到现在。历史读取范围由系统授权决定。未授权、服务不可用或读取失败会报错；无数据返回 null 或空数组。健康数据不会自行上传，但运行的脚本可以保存或通过网络发送返回值，请仅运行可信脚本。

### 外观与手表预览

设置中的“外观”可选择跟随系统、浅色或深色。手表界面保持黑色背景。
“手表预览”加载手表栏目所选的脚本，变量与文件独立保存，不需要连接手表；健身传感器需在真机上使用。

### 健身传感器

手机和手表统一通过 `andjs.health.status()` 检查健康数据能力，使用 `andjs.health.current("steps")` 或 `andjs.health.current("heartRate")` 获取本机一次传感器读数。需先授予健身传感器权限；最多等待 10 秒，无有效读数返回 `null`，读取结束或停止脚本后释放监听。
步数是重启后传感器启用期间的累计计数，不等同于当天步数或 Samsung Health 历史记录。`andjs.health.summary("steps")` 读取今日步数：手机使用 Health Connect，支持的手表使用 Health Services；手表不支持指定历史时间范围，短时间内未收到数据时返回 value: null。

### 设备控制与图片日志

新增设备内存、存储、屏幕、电源、传感器和振动能力查询；自动亮度、息屏时间与手机屏幕旋转修改需要系统设置授权。`andjs.system.vibrate(200, 128)` 可触发短振动，`andjs.system.cancelVibration()` 可取消。手表单次最多 1 秒，手机最多 5 秒。

`andjs.log.img(source, maxWidth)` 居中显示图片日志，接受图片 URL、本机 API 路径、Base64，以及 `andjs.http` 的 base64/file 响应对象。图片按比例缩小，手表限宽 160dp、单图原始数据不超过 512KB；图片预览日志总量另有限制：手表约 1 MB，手机约 4 MB。具体限制见接口声明。

手机图片预览支持点击右上角关闭按钮返回，桌面悬浮运行也可使用；手表不显示关闭按钮，点按图片关闭预览。


编辑器顶部的运行按钮直接运行当前内容，无需保存或离开编辑器。脚本修改已有参数时会写回参数，未保存的代码不会自动保存。

`andjs.system.notify(title, content, id)` 发送系统通知，返回通知编号；同编号更新通知，`andjs.system.cancelNotification(id)` 撤回通知。先在权限页授权通知，提示音与震动由系统通知设置决定。

网络响应的 `isJson` 表示成功解析 JSON，`isFile` 表示下载完整写入文件，两者均要求 HTTP 状态码为 200–299，无需另行判断 `ok`。未指定 `filename` 的下载在脚本结束、停止或报错后清理；指定文件名的下载保留。需要长期保存时指定文件名，或在脚本结束前复制到其他路径。

### 桌面小部件与手表磁贴

脚本卡片上的网格开关控制手机小部件显示；手表列表中的开关单独控制手表磁贴，同步后生效。默认关闭，显示顺序沿用对应列表排序。手机提供 2×1、1×2（最多 2 项）、2×2（最多 4 项）、2×3、3×2（最多 6 项）、2×4、4×2（最多 8 项）七种小部件，超出部分隐藏。手表磁贴最多显示前 8 项，彩色弧段沿用脚本配色，点击运行；中央应用图标打开 And JS。未选项目时保留应用入口。请在手机桌面或手表磁贴列表中添加 And JS。

每个项目以自身配色显示为紧凑卡片。小部件不定时刷新、不在后台执行脚本；允许悬浮窗后，点击手机小部件直接在桌面显示运行与交互弹窗；未授权时进入应用运行。桌面实际尺寸由启动器网格决定。

表盘支持图标复杂功能的位置可添加“And JS · 应用”或“And JS · 快捷方式”。长按表盘进入自定义，选择一个位置，再选 And JS；快捷方式会让你选择已同步脚本，不受磁贴显示开关限制。表盘上仅显示图标，点击打开应用或运行指定脚本。图标使用透明背景和统一留白，最终大小、颜色及支持的位置由表盘决定；只支持文字或数值的位置可能无法添加。脚本从手表移除后，该位置退回应用入口。

系统操作：`andjs.system.toast(text, duration)` 显示系统提示；手表可用 `andjs.system.returnToWatchFace()` 返回表盘。`andjs.system.openRecents()` 和 `andjs.system.openNotificationShade()` 需要在权限授权中开启“系统操作”无障碍服务，具体支持依设备而定。该服务不读取屏幕内容、不订阅操作事件；开启后由系统保持绑定，关闭授权即可停用。

脚本图标提供 205 个矢量图标，可按分类浏览，或搜索中文名称、英文标识及分类名称。

`andjs.system.openApp("com.android.settings")` 按包名打开本机应用，成功发起启动返回 `true`；未安装、已停用、没有启动入口或系统拒绝时抛错。手机和手表分别打开本机应用，手机手表预览打开手机应用。受系统后台启动限制，建议在脚本最后调用。

`andjs.system.speak` 和 `andjs.system.returnToWatchFace` 调用失败时记录错误日志、返回 `false`，脚本继续执行；手动停止和脚本运行超时仍会终止执行。

`andjs.text(array)` 将数组用换行符拼接为文本。`andjs.value.all()` 和 `andjs.global.all()` 返回全部参数或全局变量的独立对象快照。

## 许可证

Copyright 2026 LinnBenson.

And JS 采用 [Apache License 2.0](LICENSE) 开源。第三方组件及资源遵循各自的许可证，相关说明见 [第三方资料](docs/third-party/) 和 [随应用提供的许可证](app/src/main/assets/licenses/)。

`andjs.util.time()` 返回当前 Unix 整数秒；`andjs.util.date(format, timestamp)` 按设备本地时区格式化秒级时间戳，默认格式为 `yyyy-MM-dd HH:mm:ss`，省略时间戳时使用当前时间。
