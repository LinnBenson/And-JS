package io.todu.andjs.domain

object HttpServerApiCatalog {
    val methods=listOf(ApiMethod(
        "httpServer.upload",ApiCategory.NETWORK,"andjs.httpServer","andjs.httpServer.upload(options)",
        "启动局域网网页，接收文字并上传文件到本机 Download/。",
        listOf(
            ApiArgument("options","object，可选","省略时使用默认配置；不打印启动信息，收到的文字与上传进度仍使用默认日志。"),
            ApiArgument("options.port","number，可选","端口，默认 8080；1024–65535 的整数，被占用时抛错。"),
            ApiArgument("options.token","string，可选","访问码，默认随机生成 12 位；可指定 1–64 个非空格 ASCII 字符，不允许空字符串。在 onStart 回调中取得地址及访问码。"),
            ApiArgument("options.maxFileSizeMB","number，可选","单个文件上限，默认 100；1–1024 的整数，1 MB = 1048576 字节。不限制上传速度。"),
            ApiArgument("options.onStart","function(server)，可选","监听成功后执行一次，由脚本决定如何显示状态、地址及访问码；省略时不输出启动日志。"),
            ApiArgument("options.onText","function(text)，可选","收到 1–2000 字符文字后执行；默认 console.log(text)。"),
            ApiArgument("options.onProgress","function(upload)，可选","上传期间每秒更新，完成或失败立即回调；默认用同一个日志 ID 更新进度。自定义时替代默认进度日志。")
        ),
        "持续等待；停止脚本、关闭窗口或进入后台时结束服务，Wi-Fi 断开或 IPv4 地址变化时抛错并关闭。回调可用 exit() 结束脚本。每台设备最多一个服务，同时上传一个文件、处理两个连接。文件流式写入，不覆盖重名文件，失败或停止清理未完成文件；连续 15 秒未收到数据或单次上传超过 30 分钟时中止。",
        """andjs.httpServer.upload({
  port: 8080,
  maxFileSizeMB: 100,
  onStart: function (server) {
    console.log("服务已启动");
    console.log(server.url);
    console.log("访问码：" + server.token);
  },
  onText: function (text) {
    console.log(text);
  },
  onProgress: function (upload) {
    const detail = upload.status === "completed" ? "已保存到 Download/"
      : upload.status === "failed" ? upload.error
      : upload.percent + "%";
    andjs.log.info(upload.name + "：" + detail, upload.id);
  }
});""",
        "手机、手表 · 需连接有 IPv4 地址的 Wi-Fi",
        fields=listOf(
            ApiArgument("server.url","string","Wi-Fi IPv4 网页地址，包含端口。"),
            ApiArgument("server.port","number","实际监听端口。"),
            ApiArgument("server.token","string","网页访问码。是否输出由脚本决定。"),
            ApiArgument("server.status","string","启动成功时为 listening。"),
            ApiArgument("server.directory","string","固定为 Download/，本机下载根目录。"),
            ApiArgument("server.maxFileSizeMB","number","本次服务的单文件大小上限。"),
            ApiArgument("upload.id","string","每次上传的唯一日志 ID，同次上传保持不变。"),
            ApiArgument("upload.name","string","实际保存文件名；文件名最多 120 字符、240 个 UTF-8 字节，不含路径或控制字符。"),
            ApiArgument("upload.receivedBytes","number","已接收并写入的字节数。"),
            ApiArgument("upload.totalBytes","number","文件总字节数。"),
            ApiArgument("upload.percent","number","0–100 的整数；100 表示已成功保存。"),
            ApiArgument("upload.status","string","uploading、completed 或 failed。"),
            ApiArgument("upload.error","string | null","失败原因；正常时为 null。")
        )
    ))
}
