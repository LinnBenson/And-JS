package io.todu.andjs.domain

object HttpServerApiCatalog {
    val methods=listOf(ApiMethod(
        "httpServer.upload",ApiCategory.NETWORK,"andjs.httpServer","andjs.httpServer.upload(options)",
        "启动局域网网页，接收文字、图片日志并上传文件到本机 Download/。",
        listOf(
            ApiArgument("options","object，可选","省略时使用默认配置；不打印启动信息，收到的文字与上传进度仍使用默认日志。"),
            ApiArgument("options.port","number，可选","端口，默认 8080；1024–65535 的整数，被占用时抛错。"),
            ApiArgument("options.name","string，可选","网页名称，默认 And JS；标题、页脚和浏览器标题同步显示。空白名称使用默认值。"),
            ApiArgument("options.token","string，可选","访问码，省略或空字符串表示无密码，网页留空即可；也可指定 1–64 个非空格 ASCII 字符。在 onStart 回调中取得地址及访问码。"),
            ApiArgument("options.maxFileSizeMB","number，可选","单个文件上限，默认 100；1–1024 的整数，1 MB = 1048576 字节。不限制上传速度。"),
            ApiArgument("options.onStart","function(server)，可选","监听成功后执行一次，由脚本决定如何显示状态、地址及访问码；省略时不输出启动日志。"),
            ApiArgument("options.onText","function(text)，可选","收到 1–2000 字符文字后执行；默认 console.log(text)。"),
            ApiArgument("options.onProgress","function(upload)，可选","上传期间每秒更新，完成或失败立即回调；默认用同一个日志 ID 更新进度。自定义时替代默认进度日志。"),
            ApiArgument("options.onError","function(error)，可选","启动、服务、业务回调异常或已开始上传的文件失败时通知。fatal=false 的上传失败不关闭服务；fatal=true 在通知后仍抛错。请求校验拒绝只返回网页错误，参数校验失败直接抛错。"),
            ApiArgument("options.onClose","function(info)，可选","启动尝试结束后调用一次，包括启动失败、停止、exit()、服务异常。先关闭资源，再调用；限 250 毫秒，仅支持普通 JS 运算、console.log 及 log.info/warn/error，不可启动新任务。关闭窗口或切入后台后日志可能不可见。")
        ),
        "持续等待；停止脚本、关闭窗口或进入后台时结束服务，Wi-Fi 断开或 IPv4 地址变化时抛错并关闭。回调可用 exit() 结束脚本。每台设备最多一个服务，同时上传一个文件、处理两个连接。图像页每次接收一张不超过 2 MB 的图片，调用 andjs.log.img 显示，不保存到 Download/，沿用图片日志的尺寸、数量和总量限制。文件流式写入，不覆盖重名文件，失败或停止清理未完成文件；连续 15 秒未收到数据或单次上传超过 30 分钟时中止。",
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
    const detail = upload.status === "completed" ? (upload.kind === "image" ? "已显示到日志" : "已保存到 Download/")
      : upload.status === "failed" ? upload.error
      : upload.percent + "%";
    andjs.log.info(upload.name + "：" + detail, upload.id);
  },
  onError: function (error) {
    andjs.log.error(error.code + "：" + error.message);
  },
  onClose: function (info) {
    console.log("服务已结束：" + info.reason);
  }
});""",
        "手机、手表 · 需连接有 IPv4 地址的 Wi-Fi",
        fields=listOf(
            ApiArgument("server.url","string","Wi-Fi IPv4 网页地址，包含端口。"),
            ApiArgument("server.port","number","实际监听端口。"),
            ApiArgument("server.name","string","本次上传网页名称。"),
            ApiArgument("server.token","string","网页访问码。是否输出由脚本决定。"),
            ApiArgument("server.status","string","启动成功时为 listening。"),
            ApiArgument("server.directory","string","固定为 Download/，本机下载根目录。"),
            ApiArgument("server.maxFileSizeMB","number","本次服务的单文件大小上限。"),
            ApiArgument("upload.kind","string","file 文件上传或 image 图片日志。"),
            ApiArgument("upload.id","string","每次上传的唯一日志 ID，同次上传保持不变。"),
            ApiArgument("upload.name","string","文件名或图片名；文件名最多 120 字符、240 个 UTF-8 字节，不含路径或控制字符。"),
            ApiArgument("upload.receivedBytes","number","已接收并写入的字节数。"),
            ApiArgument("upload.totalBytes","number","文件总字节数。"),
            ApiArgument("upload.percent","number","0–100 的整数；100 表示文件已保存或图片已显示到日志。"),
            ApiArgument("upload.status","string","uploading、completed 或 failed。"),
            ApiArgument("upload.error","string | null","失败原因；正常时为 null。"),
            ApiArgument("error.code","string","START_FAILED、SERVER_ERROR、CALLBACK_ERROR 或 UPLOAD_FAILED。"),
            ApiArgument("error.message","string","错误说明。"),
            ApiArgument("error.phase","string","start、server、callback 或 upload。"),
            ApiArgument("error.fatal","boolean","是否因该错误结束服务；true 的错误仍可在 upload 外用 try/catch 捕获。"),
            ApiArgument("error.id / error.name","string | null","上传失败时为上传 ID 与文件名，其他错误为 null。"),
            ApiArgument("info.reason","string","closed 正常结束、stopped 停止或离开界面、exit 回调主动结束、error 异常结束。"),
            ApiArgument("info.message","string | null","异常结束原因；其他情况为 null。"),
            ApiArgument("info.started","boolean","是否成功创建过监听服务。启动失败时为 false。"),
            ApiArgument("info.url","string | null","监听地址；服务未创建时为 null。")
        )
    ))
}
