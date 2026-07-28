package com.example.netaudiotalk.utils

import java.net.NetworkInterface
import java.net.Inet4Address
import java.util.Collections

object NetworkInterfaceHelper {
    data class NetworkCard(val name: String, val ip: String) {
        override fun toString(): String = "$name ($ip)"
    }

    // 自动枚举当前设备所有可用的物理与虚拟网卡IPv4地址
    fun getAvailableNetworkCards(): List<NetworkCard> {
        val cards = mutableListOf<NetworkCard>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        cards.add(NetworkCard(intf.name, addr.hostAddress))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cards
    }
}
