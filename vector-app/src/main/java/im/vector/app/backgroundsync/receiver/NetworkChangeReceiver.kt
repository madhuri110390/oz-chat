//package im.vector.app.backgroundsync.receiver
//
//import android.content.Context
//import android.net.ConnectivityManager
//import android.net.Network
//import android.net.NetworkCapabilities
//import android.net.NetworkRequest
//
//
//class NetworkChangeReceiver(private val context: Context) {
//
//    private val connectivityManager =
//            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//
//    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
//        override fun onAvailable(network: Network) {
//            SyncWorker.enqueue(context)
//        }
//    }
//
//    fun register() {
//        val request = NetworkRequest.Builder()
//                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//                .build()
//        connectivityManager.registerNetworkCallback(request, networkCallback)
//    }
//
//    fun unregister() {
//        connectivityManager.unregisterNetworkCallback(networkCallback)
//    }
//}
