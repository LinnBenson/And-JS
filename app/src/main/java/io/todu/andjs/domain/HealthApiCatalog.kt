package io.todu.andjs.domain

object HealthApiCatalog {
    private fun a(name:String,type:String,description:String)=ApiArgument(name,type,description)
    private val range=listOf(a("options.start","number，可选","开始时间，Unix 毫秒；手机默认本地今天零点，手表不接受此参数。"),a("options.end","number，可选","结束时间，Unix 毫秒；手机默认现在，必须晚于 start，手表不接受此参数。"))
    val methods=listOf(
        ApiMethod("health.status",ApiCategory.HEALTH,"andjs.health","andjs.health.status()","检查本机实时传感器、今日步数与历史记录能力。",emptyList(),"object；只检查能力，不请求授权、不启动测量。","exit(andjs.health.status());","可用",listOf(
            a("source","string","healthServices（手表）或 healthConnect（手机）；预览为 preview。"),a("dailySteps","boolean","今日步数数据源是否支持；不代表已授权。"),
            a("sensors","object","本机实时传感器能力和权限。"),a("sensors.steps","boolean","是否有重启累计步数传感器。"),a("sensors.heartRate","boolean","是否有实时心率传感器。"),a("sensors.stepsGranted","boolean","是否已授权活动识别。"),a("sensors.heartRateGranted","boolean","是否已授权身体传感器。"),
            a("history","object","Health Connect 历史数据能力；手表不提供该服务。"),a("history.available","boolean","是否可使用 Health Connect。"),a("history.status","string","available / installOrUpdate / unsupported。"),a("history.granted","string[]","已授权的历史数据类型。")
        ),"手机、手表"),
        ApiMethod("health.current",ApiCategory.HEALTH,"andjs.health","andjs.health.current(type)","读取本机一次实时健康数据，统一手机与手表调用。",listOf(a("type","string","heartRate / steps；steps 是重启累计计数，今日步数请用 summary。")),"object | null；等待最多 10 秒，无有效读数为 null；无传感器或未授权抛错。停止或结束后释放监听。","exit(andjs.health.current(\"heartRate\"));","可用",listOf(a("type","string","heartRate / steps。"),a("value","number","心率或累计步数。"),a("unit","string","bpm / count。"),a("time","number","采样时间 Unix 毫秒。"),a("accuracy","number","0 不可靠、1 低、2 中、3 高；心率过滤不可靠读数。"),a("source","string","sensor。"),a("scope","string","instant（心率）或 sinceBoot（累计步数）。")),"手机、手表 · 依传感器支持"),
        ApiMethod("health.summary",ApiCategory.HEALTH,"andjs.health","andjs.health.summary(type, options)","汇总步数、距离或消耗热量；手机读取 Health Connect，手表读取 Health Services 今日步数。",listOf(a("type","string","手机 steps / distance / calories；手表目前为 steps，需活动识别授权。"),a("options","object，可选","查询时间范围。"))+range,"object；手机通过 Health Connect 聚合避免重复计数。手表限今日，不接受 start/end，能力查询最多 3 秒、等待数据最多 10 秒后注销监听；没有新数据时 value 为 null，不伪造为 0。","exit(andjs.health.summary(\"steps\"));",availability="可用",fields=listOf(a("type","string","查询类型。"),a("start","number","开始时间 Unix 毫秒。"),a("end","number","结束时间 Unix 毫秒。"),a("value","number | null","汇总数值，无数据为 null。"),a("unit","string","steps: count；distance: m；calories: kcal（总消耗）。"),a("source","string","healthConnect / healthServices；两个来源统计口径和更新时间可能不同。")),devices="手机、手表 · 手表限今日步数"),
        ApiMethod("health.read",ApiCategory.HEALTH,"andjs.health","andjs.health.read(type, options)","分页读取心率、睡眠或体重记录。",listOf(a("type","string","heartRate / sleep / weight；需对应读取授权。"),a("options","object，可选","时间范围和分页。"))+range+listOf(a("options.limit","number，可选","单页记录数，1–1000，默认 100。心率每条记录含多个采样点。"),a("options.pageToken","string，可选","上一页的 nextPageToken；翻页时保持类型、时间范围和 limit 不变。")),"object；仅读取 Health Connect 已保存的数据，不是实时传感器。可读取历史范围由系统授权决定；没有数据返回空数组，不伪造记录。","exit(andjs.health.read(\"heartRate\", {limit: 20}));",availability="可用",fields=listOf(
            a("type","string","查询类型。"),a("records","object[]","本页原始记录；不同来源可能重叠。"),a("nextPageToken","string | null","下一页标记，null 表示结束。"),
            a("records[].id","string","Health Connect 记录 ID。"),a("records[].source","string","写入该记录的应用包名。"),
            a("records[].start","number","心率/睡眠记录开始时间 Unix 毫秒。"),a("records[].end","number","心率/睡眠记录结束时间 Unix 毫秒。"),
            a("records[].samples","object[]","仅心率记录。"),a("records[].samples[].time","number","采样时间 Unix 毫秒。"),a("records[].samples[].bpm","number","每分钟心跳次数。"),
            a("records[].title","string | null","仅睡眠记录，标题可能为空。"),a("records[].stages","object[]","仅睡眠记录，各睡眠阶段。"),a("records[].stages[].start","number","阶段开始时间 Unix 毫秒。"),a("records[].stages[].end","number","阶段结束时间 Unix 毫秒。"),a("records[].stages[].stage","number","Health Connect 阶段：0 未知、1 清醒、2 睡眠、3 离床、4 浅睡、5 深睡、6 REM、7 床上清醒。"),
            a("records[].time","number","仅体重记录，测量时间 Unix 毫秒。"),a("records[].kilograms","number","仅体重记录，千克。")
        ),devices="仅手机 · Health Connect")
    )
}
