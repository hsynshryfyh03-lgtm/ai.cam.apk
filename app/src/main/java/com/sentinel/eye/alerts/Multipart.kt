package com.sentinel.eye.alerts

import java.io.File
import java.io.OutputStream

/**
 * جسم multipart/form-data بطول معروف مسبقاً، فيُرسَل Content-Length صريح بدل Transfer-Encoding: chunked
 * (أكثر توافقاً مع Telegram والوسطاء)، ويُبثّ الملف دون تحميله كاملاً في الذاكرة.
 */
class MultipartBody(
    val boundary: String,
    fields: Map<String, String>,
    fileField: String,
    private val file: File,
    mime: String
) {
    private val head: ByteArray
    private val tail: ByteArray
    val length: Long

    init {
        val sb = StringBuilder()
        for ((k, v) in fields) {
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(esc(k)).append("\"\r\n\r\n")
            sb.append(v).append("\r\n")
        }
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"").append(esc(fileField))
            .append("\"; filename=\"").append(esc(file.name)).append("\"\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n\r\n")
        head = sb.toString().toByteArray(Charsets.UTF_8)
        tail = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)
        length = head.size.toLong() + file.length() + tail.size.toLong()
    }

    private fun esc(s: String): String = s.replace("\"", "%22").replace("\r", " ").replace("\n", " ")

    fun writeTo(out: OutputStream) {
        out.write(head)
        file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.write(tail)
        out.flush()
    }
}
