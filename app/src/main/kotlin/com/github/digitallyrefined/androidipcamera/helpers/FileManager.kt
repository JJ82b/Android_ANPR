package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter

/**
 * HTTP serving for the recording-file endpoints:
 *
 *  - `GET /files`               folder index page (static shell: assets/files.html, loads /files.json)
 *  - `GET /files.json`          folder listing as JSON
 *  - `GET /files/<filename>`    download (`Content-Disposition: attachment`)
 *  - `DELETE /files/<filename>` delete, JSON result
 *
 * Storage access (MediaStore vs legacy file I/O) lives in [RecordingsHelper];
 * this class only translates HTTP requests against it.
 */
class FileManager(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    /**
     * Handles /files requests. Returns false when [path] is not a files route,
     * so the caller can fall through to its other handlers.
     */
    fun handleRequest(
        httpMethod: String,
        path: String,
        writer: PrintWriter,
        outputStream: OutputStream,
        headers: List<String> = emptyList()
    ): Boolean {
        if (path != "/files" && path != "/files.json" && !path.startsWith("/files/")) return false

        if (path == "/files") {
            if (httpMethod != "GET") {
                writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nConnection: close\r\n\r\n")
                writer.flush()
                return true
            }
            serveIndex(writer, outputStream)
            return true
        }

        if (path == "/files.json") {
            if (httpMethod != "GET") {
                writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nConnection: close\r\n\r\n")
                writer.flush()
                return true
            }
            serveFilesJson(writer)
            return true
        }

        if (path.startsWith("/files/stream/")) {
            serveStreamFile(httpMethod, path, headers, writer, outputStream)
            return true
        }

        serveFile(httpMethod, path, writer, outputStream)
        return true
    }

    private fun serveIndex(writer: PrintWriter, outputStream: OutputStream) {
        val html = try {
            context.assets.open("files.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<!DOCTYPE html><html><body><h1>Recordings</h1><p>Error loading interface.</p></body></html>"
        }
        val body = html.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/html; charset=utf-8\r\n")
        writer.print("Content-Length: ${body.size}\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Connection: close\r\n\r\n")
        // PrintWriter buffers; flush it before writing raw bytes or the body hits the wire first
        writer.flush()
        outputStream.write(body)
        outputStream.flush()
    }

    /** Serves the folder listing as JSON; assets/files.html renders it client-side. */
    private fun serveFilesJson(writer: PrintWriter) {
        val files = JSONArray().apply {
            RecordingsHelper.list(context).forEach { file ->
                put(JSONObject().apply {
                    put("name", file.name)
                    put("sizeBytes", file.sizeBytes)
                    put("lastModifiedMs", file.lastModifiedMs)
                })
            }
        }
        val json = JSONObject().apply {
            put("folder", RecordingsHelper.folderLabel(context))
            put("files", files)
        }
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.print(json.toString())
        writer.flush()
    }

    /**
     * Serves a single file: GET downloads it (attachment, never inline playback),
     * DELETE removes it from the folder.
     */
    private fun serveFile(
        httpMethod: String,
        path: String,
        writer: PrintWriter,
        outputStream: OutputStream
    ) {
        val fileName = try {
            // Uri.decode (not URLDecoder): plain percent-decoding, leaves "+" intact
            android.net.Uri.decode(path.removePrefix("/files/"))
        } catch (_: Exception) {
            null
        }
        if (fileName == null || !RecordingsHelper.isValidFileName(fileName)) {
            writer.print("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nInvalid file name\r\n")
            writer.flush()
            return
        }

        when (httpMethod) {
            "GET" -> {
                val recording = RecordingsHelper.open(context, fileName)
                if (recording == null) {
                    writer.print("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nNot Found\r\n")
                    writer.flush()
                    return
                }
                try {
                    writer.print("HTTP/1.1 200 OK\r\n")
                    // octet-stream + attachment forces a download instead of browser video playback
                    writer.print("Content-Type: application/octet-stream\r\n")
                    writer.print("Content-Length: ${recording.sizeBytes}\r\n")
                    writer.print("Content-Disposition: attachment; filename=\"${fileName.replace("\"", "")}\"\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    recording.stream.use { input -> input.copyTo(outputStream) }
                    outputStream.flush()
                } catch (e: IOException) {
                    onLog("File download aborted ($fileName): ${e.message}")
                } finally {
                    try { recording.stream.close() } catch (_: Exception) {}
                }
            }

            "DELETE" -> {
                val result = RecordingsHelper.delete(context, fileName)
                if (result == RecordingsHelper.DeleteResult.DELETED) {
                    try {
                        PlateEventStore.getInstance(context).deleteEventsForVideo(fileName)
                    } catch (_: Exception) {}
                }
                val status = when (result) {
                    RecordingsHelper.DeleteResult.DELETED -> "200 OK"
                    RecordingsHelper.DeleteResult.NOT_FOUND -> "404 Not Found"
                    RecordingsHelper.DeleteResult.FAILED -> "500 Internal Server Error"
                }
                val json = when (result) {
                    RecordingsHelper.DeleteResult.DELETED -> """{"deleted":true}"""
                    RecordingsHelper.DeleteResult.NOT_FOUND -> """{"error":"not_found"}"""
                    RecordingsHelper.DeleteResult.FAILED -> """{"error":"delete_failed"}"""
                }
                onLog("DELETE /files/$fileName -> $status")
                writer.print("HTTP/1.1 $status\r\n")
                writer.print("Content-Type: application/json\r\nConnection: close\r\n\r\n")
                writer.print(json)
                writer.flush()
            }

            else -> {
                writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, DELETE\r\nConnection: close\r\n\r\n")
                writer.flush()
            }
        }
    }

    /**
     * Serves a recording inline with HTTP Byte-Range support (`206 Partial Content`)
     * so browser `<video>` elements can seek directly to an event's `videoOffsetMs`.
     */
    private fun serveStreamFile(
        httpMethod: String,
        path: String,
        headers: List<String>,
        writer: PrintWriter,
        outputStream: OutputStream
    ) {
        if (httpMethod != "GET" && httpMethod != "HEAD") {
            writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD\r\nConnection: close\r\n\r\n")
            writer.flush()
            return
        }

        val fileName = try {
            android.net.Uri.decode(path.removePrefix("/files/stream/"))
        } catch (_: Exception) {
            null
        }
        if (fileName == null || !RecordingsHelper.isValidFileName(fileName)) {
            writer.print("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nInvalid file name\r\n")
            writer.flush()
            return
        }

        val recording = RecordingsHelper.open(context, fileName)
        if (recording == null) {
            writer.print("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nNot Found\r\n")
            writer.flush()
            return
        }

        val totalSize = recording.sizeBytes
        val contentType = when {
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            else -> "application/octet-stream"
        }

        try {
            val rangeHeader = headers.firstOrNull { hdr ->
                val colon = hdr.indexOf(':')
                colon > 0 && hdr.substring(0, colon).trim().equals("Range", ignoreCase = true)
            }?.substringAfter(':')?.trim()

            if (rangeHeader != null && totalSize > 0) {
                val range = parseByteRange(rangeHeader, totalSize)
                if (range == null) {
                    writer.print("HTTP/1.1 416 Range Not Satisfiable\r\n")
                    writer.print("Content-Range: bytes */$totalSize\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    return
                }
                val (start, end) = range
                val contentLength = end - start + 1
                writer.print("HTTP/1.1 206 Partial Content\r\n")
                writer.print("Content-Type: $contentType\r\n")
                writer.print("Accept-Ranges: bytes\r\n")
                writer.print("Content-Range: bytes $start-$end/$totalSize\r\n")
                writer.print("Content-Length: $contentLength\r\n")
                writer.print("Cache-Control: no-store\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                if (httpMethod == "GET") {
                    skipFully(recording.stream, start)
                    copyExact(recording.stream, outputStream, contentLength)
                    outputStream.flush()
                }
                return
            }

            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: $contentType\r\n")
            writer.print("Accept-Ranges: bytes\r\n")
            if (totalSize > 0) {
                writer.print("Content-Length: $totalSize\r\n")
            }
            writer.print("Cache-Control: no-store\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.flush()
            if (httpMethod == "GET") {
                recording.stream.copyTo(outputStream, 64 * 1024)
                outputStream.flush()
            }
        } catch (e: IOException) {
            onLog("Video stream aborted ($fileName): ${e.message}")
        } finally {
            try { recording.stream.close() } catch (_: Exception) {}
        }
    }

    /**
     * Parses a single `Range: bytes=start-end` header against [totalSize].
     * Returns `null` when the range is malformed or unsatisfiable.
     */
    private fun parseByteRange(rangeHeader: String, totalSize: Long): Pair<Long, Long>? {
        if (!rangeHeader.startsWith("bytes=", ignoreCase = true)) return null
        val spec = rangeHeader.substringAfter('=').substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startPart = spec.substring(0, dash).trim()
        val endPart = spec.substring(dash + 1).trim()

        val start: Long
        val end: Long
        if (startPart.isEmpty()) {
            val suffixLen = endPart.toLongOrNull() ?: return null
            if (suffixLen <= 0) return null
            start = (totalSize - suffixLen).coerceAtLeast(0L)
            end = totalSize - 1
        } else {
            start = startPart.toLongOrNull() ?: return null
            if (start < 0 || start >= totalSize) return null
            end = if (endPart.isEmpty()) {
                totalSize - 1
            } else {
                val parsedEnd = endPart.toLongOrNull() ?: return null
                parsedEnd.coerceAtMost(totalSize - 1)
            }
        }
        if (start > end) return null
        return start to end
    }

    private fun skipFully(input: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(16 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
        }
    }

    private fun copyExact(input: java.io.InputStream, output: OutputStream, bytesToCopy: Long) {
        var remaining = bytesToCopy
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read < 0) break
            output.write(buf, 0, read)
            remaining -= read
        }
    }
}
