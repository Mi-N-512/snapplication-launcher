package app.snapplication.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import java.security.MessageDigest

/*
 * Reads the current book identity from Meebook Read's exported library service, then uses
 * its exported document-opening activity to resume that exact book. It never reads the
 * reader's private files, launches private activities or changes Android permissions.
 * Related classes: RemoteActionService owns the connection; ReadingSnapshots stores the
 * returned document URI alongside a matching page; ExternalPreview opens that saved URI.
 * UI: No controls; improves the centre preview's existing Continue reading action.
 * Edit or extend: This vendor protocol was verified on Read version 2025042216. Validate
 * the APK interface and signing certificate before supporting other versions. The verified
 * metadata URI supports internal and removable storage; unsupported versions keep normal app launching.
 */
class MeebookReaderBridge(private val context: Context) {
    @Volatile private var binder: IBinder? = null
    private var bound = false
    private var supported: Boolean? = null
    private val connection = object : ServiceConnection {
        // Caches only the binding; the interface identity is checked on the capture worker.
        override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service }
        // Android may reconnect a stopped vendor process; stale handles are never reused.
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
        // A changed APK requires a new verified connection rather than retrying old transactions.
        override fun onBindingDied(name: ComponentName) { close(); supported = null }
        // Null bindings have no readable public interface and are released immediately.
        override fun onNullBinding(name: ComponentName) { close() }
    }

    // Binds on the main thread only when a recognised Meebook page actually needs the adapter.
    fun connect() {
        if (bound) return
        val allowed = supported ?: isSupported(context).also { supported = it }
        if (!allowed) return
        bound = runCatching {
            context.bindService(Intent("haoqingreader.service.action.BOOKS")
                .setComponent(ComponentName(OWNER, "com.haoqing.db.book.LibraryService")), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    // Reads one open window's path using the verified, read-only AIDL transaction off the UI thread.
    fun bookUri(readerActivity: String?): String? {
        if (readerActivity !in ACTIVITIES) return null
        val remote = binder ?: return null
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (remote.interfaceDescriptor != DESCRIPTOR) return null
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeString(readerActivity)
            // LibraryInterface.getDoubleOpenDocuments(String): exact public transaction in the pinned APK.
            if (!remote.transact(51, request, reply, 0)) return null
            reply.readException()
            val path = reply.readString()
            path?.let { ReaderBookProvider.bookUri(context.packageName, it) }?.toString()
        } catch (_: android.os.RemoteException) {
            binder = null; null
        } catch (_: RuntimeException) {
            null
        } finally { request.recycle(); reply.recycle() }
    }

    // Releases the vendor connection when Snapplication accessibility is disconnected.
    fun close() {
        binder = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }

    companion object {
        const val OWNER = "com.haoqing.app.hqreader"
        val ACTIVITIES = setOf("com.haoqing.Tab1Activity", "com.haoqing.Tab2Activity",
            "com.haoqing.Tab3Activity", "com.haoqing.Tab4Activity")
        private const val DESCRIPTOR = "com.haoqing.db.book.LibraryInterface"
        private const val VERSION = 2025042216
        private const val SIGNER = "98d7e54a6303ef7b721bada3a98ae292eec02d7b0a6aa9abd65ce9118f6b09d5"

        // Pins both app identity and version because the vendor does not publish a stable SDK contract.
        fun isSupported(context: Context): Boolean = runCatching {
            val info = context.packageManager.getPackageInfo(OWNER, PackageManager.GET_SIGNATURES)
            info.versionCode == VERSION && info.signatures?.any { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) } == SIGNER
            } == true
        }.getOrDefault(false)

        // Opens the saved, matching book through Read's public entry without granting new storage access.
        fun open(activity: Activity, savedUri: String?): Boolean {
            if (savedUri == null || !isSupported(activity)) return false
            val uri = Uri.parse(savedUri)
            if (ReaderBookProvider.bookPath(activity.packageName, uri) == null) return false
            return runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*")
                    .setComponent(ComponentName(OWNER, "com.haoqing.ReaderMainActivity"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                true
            }.getOrDefault(false)
        }
    }
}

