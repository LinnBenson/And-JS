package io.todu.andjs.domain

object HttpChatApiCatalog {
    val methods=listOf(ApiMethod(
        "httpServer.chat",ApiCategory.NETWORK,"andjs.httpServer","andjs.httpServer.chat(options)",
        "启动临时局域网公共群聊，支持文字、图片和文件。",
        listOf(
            ApiArgument("options","object，可选","省略时使用默认配置。不自动输出启动日志或聊天消息。"),
            ApiArgument("options.port","number，可选","默认 8080，1024–65535 的整数。"),
            ApiArgument("options.token","string，可选","默认空密码；网页仍先显示访问码入口，留空继续。非空时须匹配 1–64 个非空格 ASCII 字符。记录、图片及下载均须验证。"),
            ApiArgument("options.maxFileSizeMB","number，可选","单个文件上限，默认 100 MB，可设 1–1024。"),
            ApiArgument("options.maxImageSizeMB","number，可选","单张图片上限，默认 2 MB，可设 1–100。图片原样保存供预览和下载。"),
            ApiArgument("options.onStart","function(server)，可选","监听成功后调用，可输出地址或用 server.send(text) 发系统消息。"),
            ApiArgument("options.onMessage","function(message, server)，可选","用户消息完整保存后通知一次，可用 server.send(text) 回应；系统消息不触发此回调。"),
            ApiArgument("options.onError","function(error)，可选","错误包含 code、message、phase、fatal；非致命接收错误不关闭服务，致命错误通知后仍抛出。"),
            ApiArgument("options.onClose","function(info)，可选","资源关闭后通知一次，包括停止与启动失败。提供 reason、message、started、url；限 250 毫秒，仅支持普通 JS 运算和文字日志。")
        ),
        "持续等待，停止、exit()、离开运行界面或服务异常时关闭。网页每秒 HTTP 增量轮询，不使用 WebSocket。ID 由服务端分配，同一浏览器在本次服务内刷新、重连或退出后重新加入保持不变；清除 Cookie 或更换浏览器会成为新用户。记录和附件仅存应用私有目录，结束后清理；异常终止残留在下次启动时删除。单次最多 10000 条消息，数据空间为 256 MB 或单文件上限两者中较大值，满后拒绝新消息；不会静默删除已有记录。每进程最多一个 HTTP 服务，最多两个连接、一个附件上传。",
        """andjs.httpServer.chat({
  port: 8080,
  token: "",
  maxFileSizeMB: 100,
  maxImageSizeMB: 2,
  onStart: function (server) {
    console.log(server.url);
    server.send("聊天已开启");
  },
  onMessage: function (message, server) {
    console.log(message.senderId, message.type);
    if (message.type === "text" && message.text === "你好") {
      server.send("欢迎加入聊天");
    }
  },
  onError: function (error) {
    andjs.log.error(error.message);
  },
  onClose: function (info) {
    console.log("聊天已结束：" + info.reason);
  }
});""",
        "手机、手表 · 需连接有 IPv4 地址的 Wi-Fi",
        fields=listOf(
            ApiArgument("server.url / port / token / sessionId","string / number / string / string","网页地址、端口、访问码和本次服务会话 ID。"),
            ApiArgument("server.send(text)","function","发送 1–2000 字符系统消息，显示名称为“系统消息”，不再次触发 onMessage。返回消息对象。"),
            ApiArgument("message.id","number","本次会话递增消息编号。"),
            ApiArgument("message.senderId","string","服务端随机用户 ID；系统消息为 system。"),
            ApiArgument("message.type","string","text、image 或 file。"),
            ApiArgument("message.time","number","服务端时间，Unix 毫秒。"),
            ApiArgument("message.text / name / size","string | null / string | null / number | null","文字内容、附件名、附件字节数；不适用的字段为 null。"),
            ApiArgument("message.system","boolean","是否为系统消息。")
        )
    ))
}
