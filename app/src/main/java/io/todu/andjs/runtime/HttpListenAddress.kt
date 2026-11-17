package io.todu.andjs.runtime

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal object HttpListenAddress {
    fun isValid(value:String):Boolean {
        val parts=value.split('.')
        val prefix=parts.lastOrNull()=="*"
        val numbers=if(prefix) parts.dropLast(1) else parts
        return (if(prefix) numbers.size in 1..3 else numbers.size==4) && numbers.all {
            it.isNotEmpty() && it.length<=3 && it.all {c->c in '0'..'9'} && it.toInt() in 0..255
        }
    }
    fun select(value:String,addresses:List<Inet4Address>):Inet4Address {
        require(isValid(value)) {"listen 地址格式无效"}
        if(!value.endsWith(".*")) return InetAddress.getByName(value) as Inet4Address
        val prefix=value.split('.').dropLast(1).map {it.toInt()}
        return addresses.firstOrNull {address->
            prefix.indices.all {index->(address.address[index].toInt() and 255)==prefix[index]}
        } ?: error("没有匹配 listen=$value 的本机 IPv4 地址")
    }
    fun resolve(value:String):Inet4Address {
        if(!value.endsWith(".*")) return select(value,emptyList())
        val addresses=NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter {it.isUp}.flatMap {it.inetAddresses.toList().filterIsInstance<Inet4Address>()}
        return select(value,addresses)
    }
}
