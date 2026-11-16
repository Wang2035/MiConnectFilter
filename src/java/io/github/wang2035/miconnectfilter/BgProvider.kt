package io.github.wang2035.miconnectfilter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * 弹窗背景图出口：把设置页存好的图片文件以 content:// 形式给 hook 进程读。
 * 路径 /bg/<key>，key = "all"（全部设备）或设备 MAC 去分隔符大写。
 * 目标系统进程读不了本应用私有目录、也读不了 /data/local/tmp（SELinux），
 * 但经 ContentResolver 拉取导出 Provider 的文件描述符是标准跨进程通道。
 * 只读、无写接口；authority = io.github.wang2035.miconnectfilter.bg。
 */
class BgProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { BgStore.migrateIfNeeded(it) }
        return true
    }

    private fun keyOf(uri: Uri): String =
        BgStore.validKey(uri.lastPathSegment) ?: throw FileNotFoundException("bad key")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no context")
        val f = BgStore.fileFor(ctx, keyOf(uri))
        if (!f.exists()) throw FileNotFoundException("no bg image")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val ctx = context ?: return "image/*"
        val key = BgStore.validKey(uri.lastPathSegment) ?: return "image/*"
        return BgStore.mimeFor(ctx, key)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
