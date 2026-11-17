# 基础架构

## 分层与依赖

Kotlin、Jetpack Compose、Material 3。AndJsApplication 组合依赖，HomeViewModel 使用 StateFlow 管理界面状态。界面不直接读写文件，也不直接执行 JavaScript。

- domain：脚本、参数、分类和接口目录模型，以及 ScriptRepository 接口。
- data：脚本、顺序、分类和全局变量的本地原子文件存储。
- runtime：ScriptRunner 接口、参数解析和 AndJsApi 命名空间模型。
- ui：脚本首页、二级编辑页、全局变量、分类管理、接口目录。
- ui/theme：统一深浅色、语义颜色和尺寸。

RhinoScriptRunner 在 Dispatchers.Default 中为每次运行创建独立解释器上下文，使用安全标准对象和禁止 Java 类访问的 ClassShutter。单次运行 15 秒，指令观察器和 sleep 检查取消标记，限制源码、日志、显示结果及解释器栈深度。ViewModel 保持单次运行状态；运行弹窗提供停止、实时日志、返回内容与错误展示。当前仍为应用内线程，无硬内存隔离，原生运算可能延迟中止。

## 数据与持久化

每个脚本使用 UUID，在 files/scripts/<id>/ 下保存 item.json 与 main.js。item.json 是原子提交的权威文档，包含 schemaVersion、名称、分类 ID、图标 ID、颜色 ID、参数和源码；main.js 是相应源码镜像。执行时从权威文档读取固定快照，避免中断保存造成新旧内容混用。

脚本顺序保存在 scripts/order.json。拖动过程只更新内存列表，松手后保存顺序；筛选后的拖动不会改变未显示条目之间的相对顺序。

分类使用稳定 ID，显示名称可以重命名而不影响脚本归属。存在脚本的分类必须先迁移条目再移除。空分类 ID 表示“未分类”。categories.json 和 globals.json 分别保存分类和全局变量。

文件仓库适合当前单进程、少量条目。未来引入更多查询和并发写入时迁移到 Room 元数据与版本化脚本文件，保留仓库边界并按 schemaVersion 执行迁移。

## 内部 API

所有方法挂载在 andjs 对象下，按命名空间分类：

```js
andjs.value.get("enabled", false); // 当前脚本的配置
andjs.global.get("host", "");     // 全部脚本共享的变量
```

键不存在才使用默认值。false、0、空字符串和空数组均为有效配置。键不存在且未传默认值时，JS 绑定返回 undefined。配置和全局变量支持 boolean、string、number、array；数字必须有限，数组使用 JSON 文本。两个命名空间相互独立，不进行同名回退。

AndJsApi 和 AndJsValues 提供宿主侧的解析与取值规则，由 Rhino 绑定转成 JavaScript 原生值，接口目录与实现保持一致。

ApiCatalog 集中保存方法 ID、分类、命名空间、签名、说明、参数、返回规则、示例与可用状态。分类预留变量与配置、网络请求、文件与存储、界面交互、设备与系统、脚本运行、实用工具。目录支持分类筛选和搜索，未实现分类不生成虚构方法。

## 界面与交互

底部导航为脚本、全局变量、设置。首页只有搜索、自定义分类和脚本卡片，无常用和运行记录。手机默认双列，窄屏单列，宽屏三列。长按拖动具有触觉反馈、缩放、让位与落位动画。

编辑器为完整二级页面，左上角使用 chevron 返回。CodeEditor 封装等宽字体、行号、基础词法高亮、自动缩进、80 步撤销/重做、符号快捷栏及光标行列。代码和参数分为页签，未保存退出需确认。高亮不等于语法校验，当前不包含语言服务器、代码补全或诊断。

图标与颜色选择器提供 102 枚固定版本 Lucide 线性矢量图标，按常用、生活、工具、文件、网络、设备分类，可搜索；每个脚本独立保存24 种预设色之一。旧图标 ID 通过别名兼容。分类选择使用单选列表。

## 主题

品牌来自 icon.png：浅色背景 #F7F8F4、正文 #181C17、薄荷容器 #DFF8D2、深绿 #285020、青柠强调色 #C3F449。颜色通过 MaterialTheme.colorScheme 获取，补齐 secondary 与 surfaceContainer 系列，避免默认紫色混入。深色模式单独定义，不跟随壁纸动态取色。

页面边距 20dp、卡片间距 12dp、圆角 16dp。标题 22sp、条目名 15sp、导航 11sp。操作触控区以 48dp 为基准。图标资源来源与许可见 docs/third-party。

当前为手机与宽屏基础布局。根据 FEATURE_WATCH 选择精简手表界面，手机与手表共用解释器及可用宿主接口。

## 变量管理界面

全局变量和脚本参数共用 ParameterList 单行列表与 ParameterEditScreen 独立编辑页。列表支持按键或类型搜索，提供加号、编辑和删除图标。类型和值不在列表中展开。删除需要确认。

全局变量使用仓库中的已保存列表作为唯一显示来源，新增、编辑和删除成功后再刷新；保存失败保留草稿。脚本参数的修改先写入当前脚本草稿，随脚本保存提交。搜索结果携带原始索引，避免筛选后误编辑或删除其他项。

## 内置示例

应用不再预装示例。升级时按固定示例 ID 清理历史预设项目，自建脚本不受影响。

## 脚本交互

RunControl 通过 ScriptInteraction 传递请求，ViewModel StateFlow 在主线程显示弹窗，解释器线程等待响应。输入返回字符串，单选返回零起始下标，多选返回下标数组，确认返回 true；所有取消返回 null。等待时间从执行预算扣除，停止标记在等待中持续检查。停止不返回 JS 调用处。请求实例独立保存草稿，连续交互不复用旧输入。

## 变量写入

andjs.value/global 的 set/delete 通过 RunControl 的持久化回调写入仓库，成功后再替换当前运行的变量集合。value 仅更新当前脚本参数，保留脚本源码和其他元数据；global 写入全局变量仓库。单次运行阻止并行脚本，运行弹窗覆盖编辑入口。每次调用独立提交，不提供事务回滚。set 根据 JS 类型编码，delete 返回是否存在；失败不更新运行内存状态。

## 网络与平台能力

HttpSession 以 OkHttp 同步执行网络请求，每次脚本独立 Cookie 会话，结束后关闭连接池。RunControl 将停止信号转为 Call.cancel；网络等待从脚本执行预算中扣除，各请求保留独立超时与响应大小上限。默认 responseType 为 auto，Rhino 原生 JSON 解析器负责自动解析，失败则保留原文；data 请求体中的对象和数组序列化为 JSON。显式 text/base64/file 不进行 JSON 解析。

PlatformApi 隔离 Android 宿主实现。AndroidPlatformApi 提供设备信息、系统设置和权限状态，ScriptFiles 负责文件操作，app:/ 限于 filesDir/user，shared:/ 需手机权限。路径经过 canonical 校验，禁止越界和删除根目录，递归删除不跟随符号链接。系统专属入口检查 FEATURE_WATCH。接口目录记录返回字段、权限与设备适用范围。

## 手表同步与资源生命周期

WatchSnapshot 定义有版本、大小和字段校验的传输文档，移除分类信息。手机仅持久化选中 ID 顺序，内容从手机仓库生成，SHA-256 与最近收到的确认对比。Wear Data Layer 的 Asset 传输快照，接收端校验后以同目录临时文件 fsync 后原子替换，一次提交脚本、顺序和全局变量，再发送包含请求 token 和 hash 的确认 DataItem。手机不把发送成功当作应用成功，重复快照不重复覆盖手表运行变量；重试使用新序号。延迟确认必须匹配当前目标与 token。

WatchViewModel 不依赖手机仓库或通信服务，完全使用本机 WatchStore 和 RhinoScriptRunner；文件 app:/ 与网络连接也在手表本机。同步过程不会执行传入脚本。手表变量写回检查运行起始版本，避免覆盖同步后的新快照。同步上限 2MB、100 个脚本；脚本源码每份 256000 字符。

手表运行器限制日志、结果、源代码、栈深度与网络响应，检查运行期间的堆增长；这不是硬内存沙箱。HttpSession 延迟创建，运行 finally 清理连接。交互使用 CountDownLatch，停止时主动唤醒，无周期轮询。MainActivity 后台停止工作并退出任务，系统文件/授权流程保留；不调用 System.exit 或强制 GC。

HealthApi 为手机和手表提供统一的 status/current/summary/read 入口。current 复用本机传感器读取，手机 summary/read 访问 Health Connect，手表 summary(steps) 通过 Health Services STEPS_DAILY 获取今日累计值；不使用重启累计计数替代。手表按需注册 PassiveListenerCallback，限时等待并在 finally 注销，不使用后台服务。用户先从权限页授权，脚本同步等待健康数据，等待时间不计入 JS CPU 预算。步数、距离、热量使用聚合接口；心率、睡眠、体重分页读取并转换为 JS 原生对象，字段在 HealthApiCatalog 逐项定义。

LocationApi 使用本机系统定位源，校验前台权限、缓存年龄和等待超时；停止及 finally 移除监听。ConnectivityApi 提供单次网络/Wi-Fi/蓝牙快照，不扫描、不常驻监听。Health Services 最低 API 30，仅在版本检查通过的手表调用；manifest overrideLibrary 保留其他设备 API 26 支持。

## 局域网上传服务

`andjs.httpServer.upload(options)` 通过 `PlatformApi.openHttpServer` 创建 `AndroidHttpServer`。只监听已连接 Wi-Fi 的 IPv4 地址，保留其他网络路由；Wi-Fi 丢失或地址变化时关闭。每个进程最多一个上传服务，脚本停止、回调退出或异常均释放监听、连接、网络回调和未完成文件。

解释器线程负责 `onStart`、`onText` 和 `onProgress` 回调，不跨线程访问 Rhino 上下文。监听使用一个阻塞线程，请求使用最多两个空闲可回收线程，不建立请求排队线程池。文字事件队列最多八项，上传同时只允许一个。空闲时解释器阻塞等待事件；上传进度只保存最新字节计数，按一秒截止时间生成快照，完成和失败立即通知。文件接收循环复用 16 KiB 缓冲区，无主动限速或传输延时。

内置网页作为单个静态资源交付，不加载外部资源。请求头、文字和文件大小有独立上限；访问码默认为空，空值按空密码匹配；非空值继续校验，连接处理后关闭。图像请求复用单上传槽和每秒进度，最大 2 MiB；原图转换为 Base64 事件交给 Rhino 线程调用现有 log.img，不在上传链路预解码或转换缩略图，失败返回网页并通过上传失败进度通知 onError，不写入 Download/。网页使用内嵌图标，无外部依赖；浏览器本地图像预览使用 blob URL，替换和离开时释放。文件直接写入 `Download/`；Android 10 及以上通过 MediaStore pending 项接收，完整后发布并读取最终重名编号，失败时删除 pending 项；旧系统检查写入权限并原子保留唯一文件名。

上传服务的 onError 在解释器线程分发；单文件失败不关闭监听，致命错误通知后保留原有抛错行为。onClose 在资源关闭后由 finally 分发一次，覆盖启动失败与停止路径。关闭回调有 250ms 指令检查预算，仅放行文字日志函数；不清除 RunControl 的停止标记，回调结束后恢复正常取消检查。

### 临时 HTTP 群聊

`httpServer.chat` 复用 AndroidHttpServer 的 Wi-Fi 绑定、连接池和停止清理。HttpChatStore 将消息 JSON 和原始附件写入 noBackupFilesDir/http-chat 的本次 UUID 目录；消息递增 ID 用于分页增量轮询，每页 50 条，网页串行每秒拉取。附件使用 16 KiB 缓冲区接收与下载，不转换 Base64，不对图片完整解码。图片通过 bounds 检查格式后保留原始内容。

/join 校验 token 后发放随机 HttpOnly、SameSite=Strict Cookie，服务端维护 Cookie 到发送者 ID 的映射；重复加入恢复 ID。/messages 和 /attachment 均验证 Cookie，退出撤销访问权但保留本次 ID 映射。系统消息通过回调 server.send 写入同一存储，不放入 onMessage 事件队列。

关闭时先断开所有连接，最后一个工作线程结束后清理会话目录再释放进程名额，避免写入与清理竞争。应用启动及下一次新聊天创建前清理残留，异常结束的数据不会恢复。
