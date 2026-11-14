package io.todu.andjs.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthApi(private val context:Context) {
    companion object {
        val types=linkedMapOf("steps" to StepsRecord::class,"heartRate" to HeartRateRecord::class,"sleep" to SleepSessionRecord::class,"distance" to DistanceRecord::class,"calories" to TotalCaloriesBurnedRecord::class,"weight" to WeightRecord::class)
        val permissions get()=types.values.map {HealthPermission.getReadPermission(it)}.toSet()
    }
    fun availability():Int=if(Build.VERSION.SDK_INT<28 || context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) HealthConnectClient.SDK_UNAVAILABLE else HealthConnectClient.getSdkStatus(context)
    suspend fun historyStatus():Map<String,Any> {
        val availability=availability()
        val granted=if(availability==HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions() else emptySet()
        return mapOf("available" to (availability==HealthConnectClient.SDK_AVAILABLE),"status" to when(availability) {HealthConnectClient.SDK_AVAILABLE->"available";HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED->"installOrUpdate";else->"unsupported"},"granted" to types.filter {HealthPermission.getReadPermission(it.value) in granted}.keys.toList())
    }
    fun call(method:String,args:JSONArray,control:RunControl=RunControl()):Any? {
        val watch=context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        val sensors=FitnessApi(context)
        if(method=="current") {
            val type=args.getString(0)
            require(type in listOf("heartRate","steps")) {"current 支持 heartRate / steps"}
            val value=sensors.call(type,control) as? Map<*,*> ?: return null
            return mapOf("type" to type,"value" to value[if(type=="steps") "count" else "bpm"],"unit" to if(type=="steps") "count" else "bpm","time" to value["time"],"accuracy" to value["accuracy"],"source" to "sensor","scope" to if(type=="steps") "sinceBoot" else "instant")
        }
        if(method=="status") {
            val history=runBlocking {withTimeout(5000) {historyStatus()}}
            val daily=if(watch) try {WatchHealthApi(context).supported(control)} catch(_:Exception) {false} else history["available"]==true
            return mapOf("sensors" to sensors.status(),"dailySteps" to daily,"history" to history,"source" to if(watch) "healthServices" else "healthConnect")
        }
        if(watch) {
            require(method=="summary" && args.getString(0)=="steps") {"手表支持 current 实时数据和 summary 今日步数；历史记录请在手机读取"}
            val options=if(args.length()>1) args.getJSONObject(1) else JSONObject()
            require(!options.has("start") && !options.has("end")) {"手表今日步数不支持指定历史时间范围"}
            return WatchHealthApi(context).steps(control)
        }
        return runBlocking {withTimeout(10_000) {
        check(availability()==HealthConnectClient.SDK_AVAILABLE) {"此手机尚不可用 Health Connect，请在权限授权中检查"}
        val type=args.getString(0);val recordType=types[type] ?: error("不支持的健康数据类型")
        val client=HealthConnectClient.getOrCreate(context)
        check(HealthPermission.getReadPermission(recordType) in client.permissionController.getGrantedPermissions()) {"请先授权读取 $type 健康数据"}
        val options=if(args.length()>1) args.getJSONObject(1) else JSONObject()
        fun time(key:String,default:Long):Long {if(!options.has(key)) return default;val v=options.get(key);require(v is Number && v.toDouble().isFinite() && v.toDouble()%1==0.0 && v.toDouble() in 0.0..253402300799999.0) {"$key 须为 Unix 毫秒时间戳"};return v.toLong()}
        val end=Instant.ofEpochMilli(time("end",System.currentTimeMillis()))
        val start=Instant.ofEpochMilli(time("start",LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
        require(start<end) {"start 必须早于 end"}
        val range=TimeRangeFilter.between(start,end)
        if(method=="summary") {
            val result:Pair<Number?,String> = when(type) {
                "steps"->client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),range))[StepsRecord.COUNT_TOTAL] to "count"
                "distance"->client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL),range))[DistanceRecord.DISTANCE_TOTAL]?.inMeters to "m"
                "calories"->client.aggregate(AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),range))[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories to "kcal"
                else->error("summary 仅支持 steps、distance、calories")
            }
            return@withTimeout mapOf("type" to type,"start" to start.toEpochMilli(),"end" to end.toEpochMilli(),"value" to result.first,"unit" to result.second,"source" to "healthConnect")
        }
        require(method=="read")
        val rawLimit=if(options.has("limit")) options.get("limit") else 100;require(rawLimit is Number && rawLimit.toDouble()%1==0.0 && rawLimit.toDouble() in 1.0..1000.0) {"limit 必须为 1–1000 的整数"};val limit=rawLimit.toInt()
        val token=options.optString("pageToken","").ifEmpty {null}
        fun metadata(record:Record)=mapOf("id" to record.metadata.id,"source" to record.metadata.dataOrigin.packageName)
        val (records,next)=when(type) {
            "heartRate"->{val r=client.readRecords(ReadRecordsRequest(HeartRateRecord::class,range,pageSize=limit,pageToken=token));r.records.map {metadata(it)+mapOf("start" to it.startTime.toEpochMilli(),"end" to it.endTime.toEpochMilli(),"samples" to it.samples.map {s->mapOf("time" to s.time.toEpochMilli(),"bpm" to s.beatsPerMinute)})} to r.pageToken}
            "sleep"->{val r=client.readRecords(ReadRecordsRequest(SleepSessionRecord::class,range,pageSize=limit,pageToken=token));r.records.map {metadata(it)+mapOf("start" to it.startTime.toEpochMilli(),"end" to it.endTime.toEpochMilli(),"title" to it.title,"stages" to it.stages.map {s->mapOf("start" to s.startTime.toEpochMilli(),"end" to s.endTime.toEpochMilli(),"stage" to s.stage)})} to r.pageToken}
            "weight"->{val r=client.readRecords(ReadRecordsRequest(WeightRecord::class,range,pageSize=limit,pageToken=token));r.records.map {metadata(it)+mapOf("time" to it.time.toEpochMilli(),"kilograms" to it.weight.inKilograms)} to r.pageToken}
            else->error("累计数据请使用 summary，避免重复计数")
        }
        mapOf("type" to type,"records" to records,"nextPageToken" to next?.ifEmpty {null})
    }}
    }
}
