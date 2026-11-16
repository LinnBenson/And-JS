package io.todu.andjs.domain

object WebSocketApiCatalog {
    private fun field(name:String,type:String,description:String)=ApiArgument(name,if(name.startsWith("options.") && !type.contains("可选")) "$type，可选" else type,description)
    val methods=listOf(
        ApiMethod("ws.connect",ApiCategory.NETWORK,"andjs.ws","andjs.ws.connect(url, options)","建立本次脚本专用的 WebSocket 连接。",listOf(
            field("url","string","wss:// 或 ws:// 地址。建议 wss；明文 ws 受系统网络策略限制。使用系统证书校验，不跟随重定向。"),
            field("options.connectTimeout","number","建立连接最长等待时间，默认 10000 毫秒；手机 1–30000，手表 1–15000。"),
            field("options.receiveTimeout","number","receive 的默认等待时间，默认 15000 毫秒；手机 1–120000，手表 1–15000。"),
            field("options.pingInterval","number","协议心跳间隔，默认 20000 毫秒；可设 5000–60000，0 关闭。弹窗等待期间仍发送心跳；服务端不回应或网络断开时连接可能失败。"),
            field("options.headers / auth / cookies","object / object / string或object","headers 为字符串请求头；auth 支持 {type:'bearer',token:'…'} 或 basic 的 username/password。cookies 为 sid=abc 或 {sid:'abc'}，与 Cookie 请求头不能同时指定。不自动共享 andjs.http 的 Cookie，也不保存握手响应中的 Cookie。协议请求头由应用管理。"),
            field("options.protocols","string[]，可选","可提供 1–8 个不重复的 WebSocket 子协议名，例如 ['graphql-transport-ws']。")
        ),"返回连接对象，提供 send、receive、close 以及 onMessage、onError、onClose、listen。手机最多同时 4 个、每次运行最多尝试连接 32 次；手表同时 2 个、每次运行 8 次。输入和选择弹窗不关闭连接；exit、自然结束、未捕获异常、用户停止或关闭运行窗口都会自动关闭。子脚本创建的连接在子脚本结束时关闭，不可返回给父脚本继续使用。可通过 listen 串行处理消息回调，无自动重连或后台常驻。",
            "const ws = andjs.ws.connect(\"wss://example.com/socket\");\ntry {\n  const text = andjs.ui.input(\"发送消息\", \"\", \"你好\");\n  if (text === null) exit();\n  ws.send({message: text});\n  const reply = ws.receive();\n  exit(reply.type === \"text\" ? reply.data : reply.base64);\n} finally {\n  ws.close();\n}","可用 · 同步方法"),
        ApiMethod("ws.send",ApiCategory.NETWORK,"andjs.ws","ws.send(data, options)","向 connect 返回的连接发送文字、JSON 或二进制消息。",listOf(
            field("data","string | JSON值","默认字符串发送文字，其它 JSON 值编码为 JSON 文字。不接受 undefined、函数和循环引用。"),
            field("options.type","string，可选","text、json 或 base64。base64 模式将标准 Base64 字符串解码为二进制帧；json 模式可显式把字符串作为 JSON 字符串发送。")
        ),"true 表示进入发送队列，不保证服务端已处理。单条消息手机最多 256KB，手表 64KB；发送队列手机最多 1MB，手表 128KB，队列满抛 SEND_QUEUE_FULL。",
            "const ws = andjs.ws.connect(\"wss://example.com/socket\");\nws.send({type: \"query\"});\nexit(ws.receive());","可用 · 连接对象方法"),
        ApiMethod("ws.receive",ApiCategory.NETWORK,"andjs.ws","ws.receive(timeout)","按顺序取出一条消息，没有消息时等待。",listOf(
            field("timeout","number，可选","覆盖本次等待时间，毫秒整数。手机 1–120000，手表 1–15000；默认使用 connect 的 receiveTimeout。超时抛 RECEIVE_TIMEOUT，连接仍可继续使用。")
        ),"文字消息返回 {type:'text', text, data, isJson, size}，data 自动解析 JSON，非 JSON 保留原文。二进制消息返回 {type:'binary', base64, size}。前台脚本总运行时长不限，手机与手表均可持续监听。远端正常关闭时可取完已排队消息，之后抛 CLOSED；网络错误或溢出会清空队列并抛错。",
            "const ws = andjs.ws.connect(\"wss://example.com/socket\");\ntry {\n  const reply = ws.receive(10000);\n  console.log(reply);\n} catch (e) {\n  console.log(e.code, e.message);\n} finally {\n  ws.close();\n}","可用 · 连接对象方法",listOf(
            field("接收队列","容量限制","手机最多 32 条且总计 1MB，手表最多 8 条且总计 128KB。弹窗期间继续入队，超出限制抛 QUEUE_OVERFLOW 并断开；单条超限为 MESSAGE_TOO_LARGE。大小限制在网络库交付完整消息后检查，不是协议帧接收前的硬内存隔离。"),
            field("错误","WebSocketError","可读取 name、code、message、url、status；关闭错误还含 closeCode、reason。常见 code：INVALID_OPTIONS、CONNECTION_LIMIT、CONNECT_TIMEOUT、RECEIVE_TIMEOUT、HANDSHAKE_ERROR、DNS_ERROR、TLS_ERROR、CONNECTION_ERROR、CLOSED、MESSAGE_TOO_LARGE、QUEUE_OVERFLOW、SEND_QUEUE_FULL。用户停止直接结束脚本。")
        )),
        ApiMethod("ws.onMessage",ApiCategory.NETWORK,"andjs.ws","ws.onMessage(callback)","注册消息回调，调用 listen 后按顺序处理消息。",listOf(
            field("callback","function","参数与 receive 返回的消息对象相同。可发送消息、发起请求及打开弹窗。弹窗等待期间消息排队，队列限制不变。重复注册替换原回调。")
        ),"返回连接对象。注册本身不会保持脚本运行；需调用 listen。回调中 exit 结束整次运行，未捕获异常结束脚本。",
            "ws.onMessage(function (message) { console.log(message.data); });\nws.listen();","可用 · 连接对象方法"),
        ApiMethod("ws.onError",ApiCategory.NETWORK,"andjs.ws","ws.onError(callback)","注册监听期间的连接错误回调。",listOf(
            field("callback","function","参数包含 code、message、url、status 等 WebSocketError 字段。先通知 onError，再通知 onClose。")
        ),"返回连接对象。未注册时 listen 直接抛错。connect、send 等同步调用的错误仍由调用方 try/catch 处理。停止或退出后不再执行回调。",
            "ws.onError(function (error) { console.log(error.code, error.message); });","可用 · 连接对象方法"),
        ApiMethod("ws.onClose",ApiCategory.NETWORK,"andjs.ws","ws.onClose(callback)","注册连接关闭回调。",listOf(
            field("callback","function","参数为 {closeCode, reason, wasClean, url}。异常断开 closeCode 为 1006；wasClean 表示完成关闭握手。")
        ),"返回连接对象。只在 listen 分发时通知，每个连接最多一次；脚本结束时的自动清理不会再执行回调。",
            "ws.onClose(function (event) { console.log(event.closeCode, event.reason); });","可用 · 连接对象方法"),
        ApiMethod("ws.listen",ApiCategory.NETWORK,"andjs.ws","ws.listen()","等待消息并串行执行回调。",emptyList(),
            "需先注册 onMessage。连接关闭后返回关闭信息，之后的脚本才继续执行。空闲时持续等待，不使用 receiveTimeout；脚本总运行时长不限。弹窗期间只接收排队。同一连接监听期间不能调用 receive，回调中不能嵌套 listen。",
            "ws.onMessage(function (message) { console.log(message.data); });\nws.listen();","可用 · 连接对象方法"),
        ApiMethod("ws.close",ApiCategory.NETWORK,"andjs.ws","ws.close(code, reason)","关闭连接并丢弃未读取消息。",listOf(
            field("code","number，可选","默认 1000，允许 1000 或 3000–4999。"),field("reason","string，可选","默认空字符串，最多 123 个 UTF-8 字节。")
        ),"首次关闭返回 true，已关闭返回 false。最多等待 500 毫秒完成关闭握手，随后强制释放。对象不能重新打开；需再次 connect。脚本自动结束也会收尾，用户停止立即取消连接。",
            "const ws = andjs.ws.connect(\"wss://example.com/socket\");\nws.close(1000, \"done\");","可用 · 连接对象方法")
    )
}
