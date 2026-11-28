package com.magplay.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 网络类型判断：模型 1.2GB，移动网络下要先问一句。 */
object NetGuard {

    fun isCellular(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        cellular && !wifi
    } catch (t: Throwable) {
        false
    }
}
