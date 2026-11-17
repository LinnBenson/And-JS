# And JS

一个条目，一份 JavaScript。And JS 是面向 Android 的脚本快捷入口。

- 创建、编辑和删除脚本，按名称搜索，自定义分类，长按拖动排序。
- 102 枚分类图标，可搜索并为每个条目选择颜色。
- 首页可导入 JS 文件直接创建快捷方式，默认使用文件名。
- 编辑页提供 JS 文件导入、导出；导入替换当前代码，可撤销，保存后生效。文件使用 UTF-8 编码。
- 独立代码编辑页，支持行号、基础语法高亮、自动缩进、撤销/重做和符号快捷栏。
- 脚本参数和全局变量支持 boolean、string、number、array，以可搜索的单行列表管理，通过独立页面编辑，保存在本机。
- 设置中的接口声明按用途分类，先展示完整使用示例并支持复制，再说明参数、返回值和使用条件；支持搜索方法、用途、参数与示例。
- 白色主背景、玻璃导航栏与多种脚本配色，支持浅色、深色和跟随系统。

使用 Rhino 1.9.1 在后台执行 JavaScript，支持运行结果、日志、错误行号、停止操作；前台脚本不设总运行时长上限。不预装示例脚本，可新建或导入 JS 文件。

手机和手表的运行窗口打开时保持屏幕常亮，停止、完成或报错后仍保持常亮，关闭窗口后恢复系统休眠。桌面悬浮运行窗口不再在运行结束五分钟后自动关闭。

内置方法见设置 → 接口声明：`andjs.value.get`、`andjs.global.get`、`exit(value)`、`console.log`、`andjs.log.info/warn/error`、`andjs.sleep`、`andjs.util.uuid/randomInt`。参数和全局变量在启动时读取快照，脚本自身通过 set/delete 写入后可立即读取更新。

运行环境不是浏览器或 Node.js，不提供 DOM、fetch、require、定时器或 Android Java 对象。当前以同步脚本为主，不提供异步任务调度。停止通过解释器指令检查实现；个别原生运算可能延迟响应。没有独立进程或硬内存配额，请只运行可信脚本。手表提供独立的精简运行界面。

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

界面交互：`andjs.ui.input`、`select`、`multiSelect`、`confirm` 支持输入、单选、多选和确认。交互窗口可取消；单选菜单点击选项立即提交。

交互说明作为第二个参数传入，例如 `andjs.ui.input("称呼", "请输入希望显示的称呼", "朋友")`、`andjs.ui.confirm("继续？", "确认后执行下一步")`。单选和多选为 `(title, content, options)`。不传说明时保持原布局。

脚本可用 `andjs.value.set/delete` 修改自己的参数，用 `andjs.global.set/delete` 修改全局变量。`set` 自动新增或更新，支持四种已有类型；每次调用立即保存，同次运行的 `get` 可读取更新，脚本出错或停止不回滚已保存修改。

实用工具：`andjs.util.isJson(value)` 检查合法 JSON 文本，`andjs.util.isUuid(value)` 检查标准 UUID 字符串，`andjs.util.md5(text)` 返回 UTF-8 字符串的 32 位小写 MD5。

设置中的“清除缓存”会停止正在运行的脚本，清除当前运行日志、图片日志、脚本缓存（`andjs.cache`）、分享临时副本、临时下载和应用缓存目录。脚本、变量、便签、设置及主动保存的文件保留。手表收到有效同步并保存后会自动执行同样的清理；清理完成后才确认同步成功。若任务未能及时退出，会提示稍后重试，避免删除仍在使用的文件。

## 局域网传送

连接 Wi-Fi 后运行 `andjs.httpServer.upload(options)`，通过 `onStart(server)` 取得网页地址和访问码，是否输出及输出格式由脚本决定。同一局域网的设备打开网页，可发送文字到运行日志，上传文件到本机 `Download/` 根目录，或在图像页发送图片，通过 `andjs.log.img` 显示到日志；重名文件自动编号。

```js
andjs.httpServer.upload({
  port: 8080,
  maxFileSizeMB: 100,
  onStart: function (server) {
    console.log("服务已启动");
    console.log(server.url);
    console.log("访问码：" + server.token);
  }
});
```

省略 `token` 或设置为空字符串时无需密码，网页访问码留空即可；设置非空 `token` 后须输入匹配的访问码。可用 `onText(text)` 自定义文字处理，`onProgress(upload)` 自定义进度。`onError(error)` 接收启动、服务、回调及上传错误，`onClose(info)` 在服务资源关闭后通知一次，停止和启动失败也会通知。致命错误通知后仍抛出，单个文件失败可继续接收。关闭回调限 250 毫秒，仅支持普通 JS 运算和文字日志，不能重新启动任务。

上传期间每秒刷新，完成或失败立即更新；`upload.id` 在同一次上传中不变，可用于 `andjs.log.info(文字, upload.id)` 原位更新日志。

服务使用小缓冲区流式写入，不限制上传速度，同时接收一个文件，最多处理两个连接。单文件上限默认 100 MB，可设为 1–1024 MB。文字最多 2000 字符。停止脚本、关闭窗口或进入后台会关闭服务并清理未完成文件；Wi-Fi 断开或地址变化后需重新运行。`upload` 持续等待请求，回调应尽快返回。

图像页每次发送一张不超过 2 MB 的图片，仅显示到日志，不保存到下载目录；沿用图片日志的尺寸、数量和总量限制。较大的原图可通过文件页保存。上传进度的 `kind` 为 `file` 或 `image`。

手机和手表均可使用。Android 10 及以上无需额外存储授权，Android 8–9 需存储权限；手表预览使用手机网络并保存到手机下载目录。上传和群聊服务运行期间主动申请保持 Wi-Fi，结束时释放；只监听非 VPN 的 Wi-Fi IPv4 地址。手动关闭 Wi-Fi、信号丢失或系统限制仍可能导致断开。路由器须允许设备互访。HTTP 不加密，请在可信局域网使用。

## 局域网临时群聊

`andjs.httpServer.chat(options)` 在设备上开启公共群聊。网页支持文字、图片、文件和附件下载，适配手机与电脑，使用每秒一次的 HTTP 增量轮询。打开网页先验证访问码，验证前不能读取记录或附件；省略 `token` 或设为空字符串时留空继续。同一浏览器刷新或重新加入后保持原 ID，记录从设备恢复；重新启动服务后进入全新会话。

```js
andjs.httpServer.chat({
  token: "",
  onStart: function (server) {
    console.log(server.url);
    server.send("聊天已开启");
  },
  onMessage: function (message, server) {
    console.log(message.senderId, message.type);
    if (message.text === "你好") server.send("欢迎加入聊天");
  }
});
```

`port` 默认 8080，`maxFileSizeMB` 默认 100，`maxImageSizeMB` 默认 2。`server.send(text)` 发送标记为“系统消息”的文字，不触发 `onMessage`。也支持 `onError` 和 `onClose`，语义与上传服务一致。文字最多 2000 字符。

记录及附件只保存在应用私有临时目录，不进入 `Download/`。服务结束后删除；强制结束或崩溃留下的数据在下次启动应用时清理，不恢复历史会话。每次会话最多 10000 条消息，数据空间为 256 MB 或单文件上限两者中较大值，达到上限后需重新启动服务，不自动删除已有记录。上传与聊天服务共用一个进程内监听名额，同时处理最多两个连接、一个附件上传。用户清除 Cookie 或换用另一浏览器会分配新 ID。

## 网络、文件与系统

`andjs.http` 提供同步请求、上传和下载，支持查询参数、请求头、JSON、表单、文本、二进制、multipart、认证、Cookie、重定向及超时。请求在后台等待，不阻塞界面，可停止。默认使用 HTTPS，明文 HTTP 受 Android 系统策略限制。

```js
const response = andjs.http.post("https://example.com/api", {
   data: {name: "And JS", tags: ["phone", "watch"]}
});
exit(response.data);
```

`data` 为对象或数组时自动发送 JSON；响应默认自动解析 JSON，非 JSON 保持字符串。`response.text` 保留原文。可指定 `responseType: "text"`、`"json"`、`"base64"` 或 `"file"`。网络请求受各自的超时配置限制。

网络失败可通过 `try/catch` 读取结构化错误，例如 `error.code`、`phase`、`status`、`attempts` 和 `elapsedMs`。开启 `throwOnHttpError` 后，非 2xx 响应抛出 `HTTP_ERROR`，完整响应保留在 `error.response`；默认仍正常返回 HTTP 错误响应。严格 JSON 解码失败为 `INVALID_RESPONSE`。

可创建独立客户端，复用配置并隔离登录状态：

```js
const client = andjs.http.createClient({
  baseUrl: "https://example.com/api/",
  headers: {Accept: "application/json"},
  retry: {count: 2, delayMs: 500, maxDelayMs: 3000},
  totalTimeout: 15000
});
client.setCookies("./", {sid: "your-session"});
const results = client.batch([
  {url: "profile", responseType: "json"},
  {url: "messages", responseType: "json"}
], {concurrency: 2, totalTimeout: 15000});
results.forEach(item => {
  if (item.ok) console.log(item.response.data);
  else console.log(item.error.code, item.error.status);
});
```

`baseUrl` 支持相对地址，客户端请求地址须与它同源。请求级 `headers`、`query` 合并默认值，条目设为 `null` 可删除；其它配置按请求覆盖；`data/json: null` 仍发送 JSON null，指定新请求体格式会替换默认请求体。客户端和 Cookie 只在本次脚本运行内有效，不自动落盘；包含默认客户端，手机最多 8 个、手表 4 个。客户端共享文件目录，Cookie 和连接池相互独立。

`cookies` 默认 `true`，自动收发会话 Cookie；设为 `false` 禁用自动读取和保存。指定 `{sid: "abc"}` 或 `"sid=abc; lang=zh"` 可临时覆盖同名 Cookie，只对本次请求生效；服务端返回的 Cookie 仍按会话保存。手动 Cookie 不随跨域重定向发送。`setCookies(url, cookies)` 写入会话，`getCookies(url)` 读取匹配地址的 Cookie，`clearCookies()` 清空当前客户端会话。`setCookies` 接收 Cookie 请求头形式，不接收 Set-Cookie 属性串；写入的 Cookie 仅限该主机，路径为 `/`，HTTPS 地址自动标记为 secure。`cookies: false` 不删除显式提供的 `Cookie` 请求头；请求头与 `cookies` 字符串或对象不能同时指定。

重试默认关闭。`retry: 2` 表示最多额外重试两次，默认只用于 GET、HEAD、OPTIONS；可通过 `retry.methods` 显式允许其它方法，但应先确认服务端能避免重复提交。默认重试临时网络故障以及 408、429、500、502、503、504，支持指数退避和 Retry-After。`timeout` 限制每次尝试，`totalTimeout` 限制整个请求（含重试等待）；手机总预算最多 120 秒，手表 15 秒。手机最多重试 5 次，手表 2 次。

`batch` 同步等待整批完成，内部并发，按输入顺序返回 `{ok, response?, error?}`；单项失败不取消其它项。手机每批最多 20 项、并发最多 4，手表每批最多 8 项、并发最多 2。整批 `totalTimeout` 包含排队时间，超时会取消剩余任务并抛错。内存响应总预算为手机 8MB、手表 1MB，下载文件总预算为手机 256MB、手表 16MB；先为每个文件请求预留手机 256KB、手表 64KB 的错误正文空间；未指定 `maxBytes` 时按同类请求数量均分剩余预算，指定时总和不得超出预算。同一批下载不能使用相同文件名。批量请求共享所属客户端的 Cookie，有登录先后依赖时应顺序调用。

文件下载仅在 HTTP 成功后保存到目标文件；HTTP 错误正文作为 `text` 返回，不覆盖已有目标文件，错误正文最多读取手机 1MB、手表 256KB，并受 `maxBytes` 限制。接口参数和错误码可在设置 → 接口声明中查看。

`andjs.ws.connect()` 提供 WebSocket 同步收发，返回的连接支持 `send`、`receive`、`close`：

```js
const ws = andjs.ws.connect("wss://example.com/socket", {
  connectTimeout: 10000,
  receiveTimeout: 15000
});
try {
  const text = andjs.ui.input("发送消息", "", "你好");
  if (text === null) exit();
  ws.send({message: text});
  const reply = ws.receive();
  exit(reply.type === "text" ? reply.data : reply.base64);
} finally {
  ws.close();
}
```

连接在输入、单选、多选或确认弹窗期间保持打开，收到的消息按顺序排队。手动模式通过 `receive()` 获取；回调模式需先注册 `onMessage`，再调用 `listen()`。默认每 20 秒发送协议心跳，网络断开或心跳失败仍会关闭连接。`exit()`、自然结束、未捕获异常、用户停止、关闭运行窗口、后台停止和清除缓存都会释放连接；结果窗口继续显示也不会让连接继续存活。子脚本建立的连接在子脚本结束时关闭。

消息回调示例：

```js
const ws = andjs.ws.connect("wss://example.com/socket");
ws.onMessage(function (message) {
  console.log(message.data);
  const answer = andjs.ui.input("回复");
  if (answer === null) exit();
  ws.send(answer);
});
ws.onError(function (error) { console.log(error.code, error.message); });
ws.onClose(function (event) { console.log("已断开", event.closeCode); });
ws.send({type: "subscribe"});
ws.listen();
```

`listen()` 等待并串行执行回调，连接关闭后才继续后面的代码；注册回调本身不会延长脚本生命。回调可以发起 HTTP 请求及打开输入、选择弹窗。弹窗等待时网络继续接收，关闭弹窗后按顺序处理排队消息，不会并行执行 JavaScript。监听期间不能对同一连接调用 `receive()`，回调中不能嵌套 `listen()`。重复注册会替换原回调。`onError` 处理监听期间的连接错误；未注册时错误从 `listen()` 抛出。`connect`、`send` 等同步调用失败仍直接抛错。`onClose` 提供 `closeCode/reason/wasClean/url`，每个连接最多通知一次；停止或退出脚本时不再执行回调。回调内 `exit()` 结束整次运行。

`send()` 默认把字符串作为文字、其它 JSON 值编码为 JSON；二进制使用 `send(base64, {type: "base64"})`。`receive()` 返回文字消息的 `text/data/isJson`，或二进制消息的 `base64`，以及 `type/size`。等待超时抛 `RECEIVE_TIMEOUT`，可捕获后继续接收；远端正常关闭后可取完已有消息，再抛 `CLOSED`。错误提供 `code/message/url/status`，关闭错误还有 `closeCode/reason`。`send()` 成功只表示进入发送队列，不代表服务端已处理。

手机单条消息最多 256KB，接收队列最多 32 条且总计 1MB；手表单条最多 64KB，队列最多 8 条且总计 128KB。单条超限或接收队列溢出会明确报错并断开，不默默丢弃消息。限制在网络库交付完整消息后检查，无法替代硬内存隔离。手机同时最多 4 个连接、每次运行最多 32 次连接尝试；手表分别为 2 个和 8 次。手机和手表均可在前台持续监听，不设脚本总运行时长上限。

WebSocket 可配置握手请求头、Basic/Bearer 认证、Cookie 和子协议，不自动共享 HTTP 客户端的 Cookie，不跟随重定向，不自动重连或后台常驻。推荐使用 `wss://`；`ws://` 受系统明文网络策略限制。`close()` 最多等待 500 毫秒完成握手，随后释放；用户停止会立即取消。

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

手表使用黑色背景，默认三列宫格，可切换为列表并记住选择；提供运行与结果页，保留输入、单选、多选和确认，不提供分类、源码查看或编辑。同步最多 100 项、总计 2MB，每份源码最多 256000 字符；日志保留 40 条，结果显示最多 8000 字符。内存网络响应最多 2MB、文件下载最多 16MB，普通文件读取最多 2MB。手表网络请求单次最多 15 秒，脚本总运行时长不限。JS 解释器仍在应用进程内，资源检查不能替代硬内存隔离。

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

`andjs.log.img(source, id, maxWidth)` 居中显示图片日志，接受图片 URL、本机 API 路径、Base64，以及 `andjs.http` 的 base64/file 响应对象。图片按比例缩小，手表限宽 160dp、单图原始数据不超过 2MB；图片预览日志总量另有限制：手表约 1 MB，手机约 4 MB。具体限制见接口声明。

手机图片预览支持点击右上角关闭按钮返回，桌面悬浮运行也可使用；手表不显示关闭按钮，点按图片关闭预览。


编辑器顶部的运行按钮直接运行当前内容，无需保存或离开编辑器。脚本修改已有参数时会写回参数，未保存的代码不会自动保存。

`andjs.system.notify(title, content, id)` 发送系统通知，返回通知编号；同编号更新通知，`andjs.system.cancelNotification(id)` 撤回通知。先在权限页授权通知，提示音与震动由系统通知设置决定。

网络响应的 `isJson` 表示成功解析 JSON，`isFile` 表示下载完整写入文件，两者均要求 HTTP 状态码为 200–299，无需另行判断 `ok`。未指定 `filename` 的下载在脚本结束、停止或报错后清理；指定文件名的下载保留。需要长期保存时指定文件名，或在脚本结束前复制到其他路径。

### 桌面小部件与手表磁贴

脚本卡片上的网格开关控制手机小部件显示；手表列表中的开关单独控制手表磁贴，同步后生效。默认关闭，显示顺序沿用对应列表排序。手机提供 2×1、1×2（最多 2 项）、2×2（最多 4 项）、2×3、3×2（最多 6 项）、2×4、4×2（最多 8 项）七种小部件，超出部分隐藏。手表磁贴最多显示前 8 项，彩色弧段沿用脚本配色，点击运行；中央应用图标打开 And JS。未选项目时保留应用入口。请在手机桌面或手表磁贴列表中添加 And JS。

每个项目以自身配色显示为紧凑卡片。小部件不定时刷新、不在后台执行脚本；允许悬浮窗后，点击手机小部件直接在桌面显示运行与交互弹窗；未授权时进入应用运行。桌面实际尺寸由启动器网格决定。

表盘支持图标复杂功能的位置可添加“And JS · 应用”或“And JS · 快捷方式”。长按表盘进入自定义，选择一个位置，再选 And JS；快捷方式会让你选择已同步脚本，不受磁贴显示开关限制。表盘上仅显示图标，点击打开应用或运行指定脚本。图标使用透明背景和统一留白，最终大小、颜色及支持的位置由表盘决定；只支持文字或数值的位置可能无法添加。脚本从手表移除后，该位置退回应用入口。

系统操作：`andjs.system.toast(text, duration)` 显示系统提示；手表可用 `andjs.system.returnToWatchFace()` 返回表盘。`andjs.system.openRecents()` 和 `andjs.system.openNotificationShade()` 需要在权限授权中开启“系统操作”无障碍服务，具体支持依设备而定。该服务不读取屏幕内容、不订阅操作事件；开启后由系统保持绑定，关闭授权即可停用。

手机和手表的文字日志、结果及错误信息可点击复制到本机剪贴板；每次复制所点击日志条目的完整文字或完整结果。图片日志仍点击预览。

脚本图标提供 205 个矢量图标，可按分类浏览，或搜索中文名称、英文标识及分类名称。

`andjs.system.openApp("com.android.settings")` 按包名打开本机应用，成功发起启动返回 `true`；未安装、已停用、没有启动入口或系统拒绝时抛错。手机和手表分别打开本机应用，手机手表预览打开手机应用。受系统后台启动限制，建议在脚本最后调用。

`andjs.system.speak` 和 `andjs.system.returnToWatchFace` 调用失败时记录错误日志、返回 `false`，脚本继续执行；手动停止和脚本运行超时仍会终止执行。

`andjs.text(array)` 将数组用换行符拼接为文本。`andjs.value.all()` 和 `andjs.global.all()` 返回全部参数或全局变量的独立对象快照。

## 许可证

Copyright 2026 LinnBenson.

And JS 采用 [Apache License 2.0](LICENSE) 开源。第三方组件及资源遵循各自的许可证，相关说明见 [第三方资料](docs/third-party/) 和 [随应用提供的许可证](app/src/main/assets/licenses/)。

`andjs.util.time()` 返回当前 Unix 整数秒；`andjs.util.date(format, timestamp)` 按设备本地时区格式化秒级时间戳，默认格式为 `yyyy-MM-dd HH:mm:ss`，省略时间戳时使用当前时间。

`andjs.system.closeApp()` 可关闭 And JS 的弹窗、悬浮窗口和应用任务，并结束当前应用进程。调用后的脚本代码不再执行；未保存的编辑会丢失，已保存的数据保留。手机手表预览中调用也会关闭手机上的 And JS。

### Wi-Fi 与蓝牙列表

```js
const wifi = andjs.device.wifiList({refresh: true, timeout: 10000});
console.log(wifi.status, wifi.items);
const paired = andjs.device.bluetoothList();
const classic = andjs.device.bluetoothList({mode: "classic", timeout: 15000});
const ble = andjs.device.bluetoothList({mode: "ble", timeout: 10000});
console.log(paired.items, classic.items, ble.items);
```

先在“设置 → 权限授权”授予精确位置和蓝牙权限，并开启系统定位与相应无线开关。已配对列表不需要位置授权；扫描需要。手机与手表均使用自身硬件，不由手机代扫。

Wi-Fi 的 `refresh: false` 只读取缓存；系统限流、拒绝或刷新超时时也会返回缓存，通过 `status`、`refreshed` 和每项 `ageMs` 区分。经典蓝牙只能发现处于可发现状态的设备，BLE 只能发现正在广播的设备；已配对不表示当前已连接。扫描最长 15 秒，可用 `maxItems` 限制数量，手机最多 100 项、手表 40 项。停止脚本会清理监听并结束本次蓝牙扫描；已提交给系统的 Wi-Fi 扫描无法主动取消。接口不配对、不连接，也不读取密码。

### BLE 广播与持续收发

手机和手表均可独立运行，直接使用本机蓝牙硬件。先在“权限授权”中授权蓝牙；扫描还需要精确位置与系统定位开启。设备是否支持广播取决于其硬件。

持续接收附近广播：

```js
const scan = andjs.ble.scan({service: "180D", interval: 1000});
scan.onMessage(function (event) {
  console.log(event.address, event.rssi, event.hex);
});
scan.onError(function (error) { console.log(error.code, error.message); });
scan.listen();
```

连接设备并订阅通知（地址及 UUID 须换成设备实际提供的值）：

```js
const device = andjs.ble.connect("AA:BB:CC:DD:EE:FF", {timeout: 10000});
console.log(device.services());
device.onMessage(function (event) {
  console.log(event.hex);
  // 也可以在这里调用 andjs.ui.input、select 等交互接口。
});
device.onError(function (error) { console.log(error.code, error.status); });
device.onClose(function (info) { console.log(info.code); });
device.subscribe("FFF0", "FFF2");
device.send("FFF0", "FFF1", "010203", {encoding: "hex"});
device.listen();
```

`read(service, characteristic)` 读取特征值；`send`/`write` 写入；`unsubscribe` 取消订阅；`mtu(size)` 请求协商 MTU。默认 MTU 23，一次最多写 20 字节；协商后最多写 `min(512, MTU−3)` 字节，不自动分包。数据支持 text、hex、base64，接收事件包含三种表示及 size；二进制应使用 hex 或 base64。同步操作失败可用 try/catch 处理，超时会关闭连接。设备要求配对时需先在系统设置中完成，不自动配对或重连。

广播发送：

```js
const ad = andjs.ble.advertise({service: "FFF0", data: "010203", encoding: "hex"});
ad.listen();
```

也可用 `manufacturerId` 替代 `service` 发送厂商数据；采用传统广播，整个广播包最多 31 字节，UUID 与协议字段也占用空间。过大或硬件不支持时会报错。广播无需连接，不提供 GATT 服务端；广播对象本身不接收数据，要监听其它设备广播请使用 `scan`。修改广播内容需先 close 再创建。

`onMessage` 注册后用 `listen()` 持续分发，或用 `receive(timeout)` 手动接收（超时返回 null）。回调串行执行，输入或选择弹窗期间消息继续排队，关闭弹窗后再处理。手机队列最多 32 条且编码事件总量不超过 128KB，手表 8 条且 32KB；溢出明确报错并关闭，不无限积压。扫描默认对同一地址每秒最多报告一次，间隔内重复广播会忽略。建议使用 service/address 过滤。

前台脚本不再设总运行时长上限；单次操作仍有超时与资源限制。停止脚本、关闭运行窗口、进入后台、退出应用或调用 exit 都会停止扫描与广播、断开 GATT，并停止回调。子脚本创建的 BLE 对象只在该子脚本执行期间有效。没有新增后台服务、自启动或自动恢复机制。


### 实时设备接口

手机与手表使用各自的硬件和系统权限，无需手机转发。可用能力通过 `status()` 查询；没有硬件、未授权或系统不支持时会抛出 `DeviceError`，包含 `code` 和 `message`。

| 接口 | 用途 |
| --- | --- |
| `andjs.sensors.watch({type, interval})` | 加速度、陀螺仪、光线、气压等实时传感器；`status().types` 列出本机支持类型 |
| `andjs.network.watch()` | 默认网络连接、网络类型、计费与互联网验证状态 |
| `andjs.torch.open()` | 开启闪光灯，`set(boolean)` 切换、`close()` 关闭；需要相机权限 |
| `andjs.nfc.watch()` | 前台读取 NFC 标签；`write(id, text, options)` 覆盖写入 NDEF 文本或 URI |
| `andjs.dnd.open({mode: "priority"})` | 临时勿扰，支持 priority / alarms / none；需要勿扰访问授权 |

```js
const sensor = andjs.sensors.watch({type: "accelerometer", interval: 200});
sensor.onMessage(function (event) {
  console.log(event.values);
});
sensor.listen();
```

`onMessage` 注册处理器，`listen()` 串行分发并等待关闭；也可调用 `receive(timeout)` 获取一次事件。回调中可以调用输入、选择等交互接口，但不能嵌套 `listen()`。传感器、网络与标签事件会合并为最新数据，弹窗期间不会无限积压，也不保证逐个事件交付。若要同时处理多个句柄，可轮流调用各自的 `receive`；单个 `listen` 只分发该句柄。手电筒和勿扰的 `listen()` 无需消息处理器。

```js
const reader = andjs.nfc.watch();
reader.onMessage(function (tag) {
  if (tag.type === "tag") console.log(tag.records);
});
reader.listen();
```

NFC 需要应用页面在前台，手表支持 NFC 支付不代表开放标签读写。写入只支持已格式化、可写且容量足够的 NDEF 标签；会覆盖原消息，不支持支付卡、门禁模拟、格式化或永久锁定。写入超时或停止无法撤销已经写入的数据，应重新读取确认。手电筒需要真实闪光灯，没有相机的手表通常不支持。

脚本结束、停止、关闭运行窗口或进入后台时释放监听、关闭手电筒并撤销临时勿扰，不新增后台服务或自动启动。Android 10 及以上只移除本应用创建的勿扰规则，旧系统在勿扰值未被改动时恢复原值。进程被系统强杀时临时勿扰规则可能保留，可在系统设置关闭；应用下次启动也会清理。完整参数、返回字段及错误码见应用内“接口声明”。


### 更新同一条日志

`andjs.log.info(value, id)`、`warn(value, id)`、`error(value, id)` 和 `img(source, id, maxWidth)` 共用日志 ID。第一次使用 ID 创建日志，之后在原位置替换内容和类型，不增加条数。ID 为非空字符串或有限数字，数字 `1` 与字符串 `"1"` 等价；只在本次运行中有效，子脚本共享本次运行的日志 ID。省略 ID 或传入 `null` 时仍追加。

```js
andjs.log.info("正在加载", "status");
andjs.log.error("请求失败", "status");
andjs.log.img("app:/photo.png", "status", 160);
andjs.log.info("已完成", "status");
```

图片替换成功后释放旧图片的日志配额，加载失败保留原日志。达到日志条数上限仍可以更新已有 ID。图片数及图片总量上限按替换后的内容计算。

从 1.1.50 起，文本日志的第二个参数是 ID，不再拼接到内容；多个值请先组合为字符串或数组，或使用仍支持多值追加的 `console.log(...values)`。图片宽度移到第三个参数，原来的 `andjs.log.img(source, 160)` 请改为 `andjs.log.img(source, null, 160)`。


### TCP 端口扫描

`andjs.network.scanPorts(options)` 检查单个 IP 或域名的 TCP 端口。手机和手表独立使用本机当前网络路由；需能访问目标，不自动连接 Wi-Fi，也不绕过 VPN、防火墙或访客网络隔离。仅扫描自己拥有或获准检测的设备。

```js
const result = andjs.network.scanPorts({
  host: "192.168.1.1",
  ports: [22, 53, 80, 443, 8080],
  timeout: 800,
  concurrency: 4
});
exit(result);
```

结果包含实际目标 `address`、按输入顺序排列的 `items`、开放端口列表 `openPorts` 和总耗时 `elapsedMs`。每项含 `port`、`status`、`elapsedMs`，失败时另含 `message`。`open` 表示连接成功，`closed` 表示明确拒绝，`timeout` 表示超时，`unreachable` 表示无路由，`error` 表示其他错误；超时不能判定端口关闭，也不能根据端口开放推断服务协议。

`ports` 必填，范围 1–65535，重复值去重；手机每次最多传 1024 项，手表 256 项。`timeout` 是单端口连接超时，1–10000ms，默认 800。并发默认手机 8、手表 4，上限分别为 16、4；应用同时最多占用 16 个扫描名额，超出返回 `BUSY`。域名解析最多等待 5 秒，只扫描第一个解析地址；要检查其他地址请直接传对应 IP。

扫描只建立 TCP 连接并立即关闭，不发送数据、不读取服务信息，不提供 UDP 扫描或自动重试。调用同步返回，界面仍可操作；停止脚本、关闭运行窗口或进入后台会关闭扫描连接，不返回部分结果。参数和扫描错误通过 `DeviceError` 的 `code`、`message` 读取。
