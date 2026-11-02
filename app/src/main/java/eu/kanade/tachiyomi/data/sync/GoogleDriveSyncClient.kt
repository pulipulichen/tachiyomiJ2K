package eu.kanade.tachiyomi.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal Drive v3 client: the only scope required is drive.appdata.
 * Call from an IO thread, never from the UI thread.
 */
class GoogleDriveSyncClient(private val accessToken: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class DriveAccount(val user: DriveUser? = null)

    @Serializable
    data class DriveUser(val permissionId: String? = null, val emailAddress: String? = null)

    @Serializable
    data class DriveFile(val id: String, val name: String)

    @Serializable
    data class DriveFiles(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

    fun account(): DriveUser {
        val body = request("GET", "https://www.googleapis.com/drive/v3/about?fields=user(permissionId,emailAddress)")
        return json.decodeFromString<DriveAccount>(body).user
            ?: throw IOException("Google Drive did not return an account identity")
    }

    fun listFiles(): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder")
                append("&pageSize=1000&fields=nextPageToken,files(id,name)")
                if (pageToken != null) append("&pageToken=").append(encode(pageToken!!))
            }
            val page = json.decodeFromString<DriveFiles>(request("GET", url))
            result.addAll(page.files.filter { it.name.startsWith("tachiyomi-j2k-sync-v1-") && it.name.endsWith(".json") })
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    fun readFile(fileId: String): String =
        request("GET", "https://www.googleapis.com/drive/v3/files/${encode(fileId)}?alt=media")

    fun createFile(name: String, content: String): String {
        val boundary = "tachiyomi-sync-" + java.util.UUID.randomUUID().toString()
        val payload = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append("{\"name\":\"").append(name).append("\",\"parents\":[\"appDataFolder\"],\"mimeType\":\"application/json\"}")
            append("\r\n--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n--").append(boundary).append("--\r\n")
        }
        val response = request(
            "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name",
            payload,
            "multipart/related; boundary=$boundary",
        )
        return json.decodeFromString<DriveFile>(response).id
    }

    fun updateFile(fileId: String, content: String) {
        request(
            "PATCH",
            "https://www.googleapis.com/upload/drive/v3/files/${encode(fileId)}?uploadType=media",
            content,
            "application/json; charset=utf-8",
        )
    }

    private fun request(method: String, url: String, body: String? = null, contentType: String = "application/json"): String {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
        if (body == null) builder.get() else builder.method(method, body.toRequestBody(contentType.toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            val data = response.body.string()
            if (!response.isSuccessful) {
                // Avoid including request headers or bearer tokens in error messages.
                val message = runCatching {
                    json.parseToJsonElement(data).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull() ?: response.message
                throw IOException("Google Drive HTTP ${response.code}: ${message.take(300)}")
            }
            return data
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
