package io.todu.andjs.domain

enum class ApiCategory(val title:String) {
    VALUES("变量与配置"), NETWORK("网络请求"), STORAGE("文件与存储"), UI("界面交互"), DEVICE("设备与系统"), HEALTH("健康数据"), SCRIPTS("脚本运行"), UTILITIES("实用工具")
}
data class ApiArgument(val name:String,val type:String,val description:String)
data class ApiMethod(val id:String,val category:ApiCategory,val namespace:String,val signature:String,val summary:String,val arguments:List<ApiArgument>,val returns:String,val example:String,val availability:String,val fields:List<ApiArgument> = emptyList(),val devices:String="通用")
object ApiCatalog {
    val methods=listOf(
        ApiMethod("value.get",ApiCategory.VALUES,"andjs.value","andjs.value.get(key, defaultValue)","读取当前脚本的配置参数。",listOf(ApiArgument("key","string","参数键，区分大小写。"),ApiArgument("defaultValue","任意类型，可选","仅在键不存在时返回。")),"返回配置对应的 boolean、string、number 或 array。false、0、空字符串和空数组不会触发默认值。键不存在且未提供默认值时返回 undefined。","const enabled = andjs.value.get(\"enabled\", false);\nconst tags = andjs.value.get(\"tags\", []);","可用 · 每次运行读取独立快照"),
        ApiMethod("global.get",ApiCategory.VALUES,"andjs.global","andjs.global.get(key, defaultValue)","读取所有脚本共享的全局变量。",listOf(ApiArgument("key","string","全局变量键，区分大小写。"),ApiArgument("defaultValue","任意类型，可选","仅在键不存在时返回。")),"取值规则与 andjs.value.get 相同。全局变量与脚本参数是独立空间，同名键互不覆盖。","const host = andjs.global.get(\"host\", \"\");\nconst retries = andjs.global.get(\"retries\", 3);","可用 · 每次运行读取独立快照")
    ) + listOf(
        ApiMethod("exit",ApiCategory.SCRIPTS,"exit","exit(value)","立即终止脚本并返回结果。",listOf(ApiArgument("value","任意类型，可选","true 显示“执行成功”，false 显示“执行失败”。对象和数组按三空格缩进的 JSON 格式化展示，最多 32000 字符。不传或传 undefined 表示无返回内容。")),"终止执行，不返回到调用处。未调用时不显示最后一个表达式的值。","exit({ message: \"完成\", count: 3 });","可用"),
        ApiMethod("console.log",ApiCategory.SCRIPTS,"console","console.log(...values)","打印日志，等效于 andjs.log.info。",listOf(ApiArgument("values","任意类型","支持多个值，日志限制与 andjs.log.info 相同。")),"undefined。","console.log(\"消息\", 123);","可用"),
        ApiMethod("sleep",ApiCategory.SCRIPTS,"andjs","andjs.sleep(ms)","暂停当前脚本，不阻塞界面。",listOf(ApiArgument("ms","number","0–15000 毫秒；等待时间计入单次 15 秒上限。")),"undefined；等待期间可以停止。","andjs.sleep(500);\nexit(\"完成\");","可用 · 同步方法"),
        ApiMethod("util.isJson",ApiCategory.UTILITIES,"andjs.util","andjs.util.isJson(value)","判断变量是否为合法 JSON 文本。",listOf(ApiArgument("value","任意类型","仅字符串参与 JSON 解析。对象、数组、数字、布尔值和 null 的合法 JSON 文本均可；非字符串返回 false。")),"boolean。空文本、尾随内容和无效 JSON 返回 false。","console.log(andjs.util.isJson('{\\\"name\\\":\\\"And JS\\\"}'));","可用"),
        ApiMethod("util.isUuid",ApiCategory.UTILITIES,"andjs.util","andjs.util.isUuid(value)","判断变量是否为标准 UUID 格式字符串。",listOf(ApiArgument("value","任意类型","检查 8-4-4-4-12 十六进制格式，大小写均可；不接受空格、花括号或 URN 前缀，不限制版本。")),"boolean。非字符串返回 false。","exit(andjs.util.isUuid(andjs.util.uuid()));","可用"),
        ApiMethod("util.md5",ApiCategory.UTILITIES,"andjs.util","andjs.util.md5(text)","计算字符串的 MD5。",listOf(ApiArgument("text","string","使用 UTF-8 编码，支持中文及空字符串，不自动转换其他类型。")),"32 位小写十六进制字符串。","exit(andjs.util.md5(\"hello\"));","可用"),
        ApiMethod("util.uuid",ApiCategory.UTILITIES,"andjs.util","andjs.util.uuid()","生成随机 UUID。",emptyList(),"string。","exit(andjs.util.uuid());","可用"),
        ApiMethod("util.randomInt",ApiCategory.UTILITIES,"andjs.util","andjs.util.randomInt(min, max)","生成包含两端的随机整数，使用系统安全随机源。",listOf(ApiArgument("min","number","最小整数，不小于 -10 亿。"),ApiArgument("max","number","最大整数，不大于 10 亿，且不小于 min。")),"number。","exit(andjs.util.randomInt(1, 6));","可用")
    ) + listOf(
        ApiMethod("ui.input",ApiCategory.UI,"andjs.ui","andjs.ui.input(title, content, defaultValue)","要求用户输入文字，等待提交后继续脚本。",listOf(ApiArgument("title","string","标题，最多显示 200 字符。"),ApiArgument("content","string，可选","标题下的说明，默认空字符串，最多 2000 字符。"),ApiArgument("defaultValue","string，可选","默认文字，默认空字符串，最多 10000 字符。")),"提交返回 string；取消返回 null。等待不计入执行超时。","const text = andjs.ui.input(\"输入名称\", \"填写你的称呼\", \"朋友\");\nexit(text);","可用 · 同步等待"),
        ApiMethod("ui.select",ApiCategory.UI,"andjs.ui","andjs.ui.select(title, content, options)","点击菜单选项立即提交。",listOf(ApiArgument("title","string","标题。"),ApiArgument("content","string，可选","标题下的说明，默认空字符串，最多 2000 字符。"),ApiArgument("options","string[]","1–200 个选项，每项最多 500 字符。")),"返回从 0 开始的选项下标；取消返回 null。","const i = andjs.ui.select(\"选择\", \"请选择一项\", [\"选项一\", \"选项二\"]);\nexit(i);","可用 · 同步等待"),
        ApiMethod("ui.multiSelect",ApiCategory.UI,"andjs.ui","andjs.ui.multiSelect(title, content, options)","要求用户选择多项。",listOf(ApiArgument("title","string","标题。"),ApiArgument("content","string，可选","标题下的说明，默认空字符串，最多 2000 字符。"),ApiArgument("options","string[]","1–200 个选项，每项最多 500 字符。")),"返回按原选项顺序排列的下标数组；未选任何项为 []，取消为 null。","exit(andjs.ui.multiSelect(\"多选\", \"可选择多项\", [\"苹果\", \"香蕉\"]));","可用 · 同步等待"),
        ApiMethod("ui.confirm",ApiCategory.UI,"andjs.ui","andjs.ui.confirm(title, content)","要求用户确认后再继续。",listOf(ApiArgument("title","string","确认标题。"),ApiArgument("content","string，可选","标题下的说明，默认空字符串，最多 2000 字符。")),"确定返回 true；取消或返回键返回 null。","exit(andjs.ui.confirm(\"确认继续？\"));","可用 · 同步等待")
    ) + listOf("value","global").flatMap {space->listOf(
        ApiMethod("$space.set",ApiCategory.VALUES,"andjs.$space","andjs.$space.set(key, value)","新增或修改${if(space=="value") "当前脚本参数" else "全局变量"}并立即保存。",listOf(ApiArgument("key","string","变量键，1–200 字符。"),ApiArgument("value","boolean/string/number/array","自动确定类型，可更改已有类型。数字须有限，数组须为 JSON 值。")),"undefined。保存失败抛出错误；保存后 get 立即读取新值。脚本之后退出或报错不会撤销已保存修改。","andjs.$space.set(\"count\", 1);\nexit(andjs.$space.get(\"count\"));","可用"),
        ApiMethod("$space.delete",ApiCategory.VALUES,"andjs.$space","andjs.$space.delete(key)","删除${if(space=="value") "当前脚本参数" else "全局变量"}并立即保存。",listOf(ApiArgument("key","string","要删除的变量键。")),"删除成功返回 true，键不存在返回 false；保存失败抛出错误。","const removed = andjs.$space.delete(\"count\");\nconsole.log(removed);","可用")
    )} + listOf("info","warn","error").map {level ->
        ApiMethod("log.$level",ApiCategory.SCRIPTS,"andjs.log","andjs.log.$level(...values)","记录本次运行的日志，运行时在弹窗中实时查看。",listOf(ApiArgument("values","任意类型","多个值以空格分隔；最多 200 条，每条 2000 字符。")),"undefined。日志仅保存在本次结果中，不创建运行历史。","andjs.log.$level(\"消息\", 123);","可用")
    } + listOf(ApiMethod("log.img",ApiCategory.SCRIPTS,"andjs.log","andjs.log.img(source, maxWidth)","在日志中居中显示图片，等比例限宽。",listOf(
        ApiArgument("source","string | object","HTTPS 图片地址、app:/ 或 shared:/ 本机路径、纯 Base64、data:image/...;base64,...；也可直接传入 andjs.http 的 base64 或 file 模式响应。共享文件仅手机且需授权。"),
        ApiArgument("source.base64","string，可选","网络请求以 responseType: base64 返回的图片编码。"),
        ApiArgument("source.file","string，可选","网络请求以 responseType: file 返回的下载文件名；相对 app:/network/。base64 优先。"),
        ApiArgument("maxWidth","number，可选","最大显示宽度 dp，32–320；手机默认 240，手表默认及上限 160。受容器宽度约束，不放大小图。")
    ),"undefined；同步加载、解码后追加一条图片日志。支持系统可解码的 PNG/JPEG/WebP 等位图，动图只显示首帧，不支持 SVG。手机单图原始数据最多 2MB、每次最多 12 张；手表 512KB、4 张；图片也计入总日志上限。原图不超过 4000 万像素，解码缩略图最长边手机 640 / 手表 320 像素；预览编码超出手机 768KB / 手表 192KB 时拒绝。加载失败抛错，不创建历史记录。","const r = andjs.http.get(\"https://example.com/icon.png\", {responseType: \"base64\"});\nandjs.log.img(r, 160);\nandjs.log.img(\"app:/photo.png\");","可用")) + ExtendedApiCatalog.methods + HealthApiCatalog.methods + DeviceApiCatalog.methods + ConnectivityApiCatalog.methods
}
