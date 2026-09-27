package com.kostas.reclaim

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CloudSyncPreferences {
    private const val PREFS = "reclaim_cloud_sync"
    private const val SERVER = "server_url"
    private const val WORKSPACE = "workspace_id"
    private const val OWNER_TOKEN = "owner_token"
    private const val DEVICE_ID = "device_id"
    private const val DEVICE_TOKEN = "device_token"
    private const val DEVICE_NAME = "device_name"

    fun serverUrl(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SERVER, "") ?: ""
    fun workspaceId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WORKSPACE, "") ?: ""
    fun ownerToken(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(OWNER_TOKEN, "") ?: ""
    fun deviceId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DEVICE_ID, "") ?: ""
    fun deviceToken(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DEVICE_TOKEN, "") ?: ""
    fun deviceName(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DEVICE_NAME, defaultDeviceName()) ?: defaultDeviceName()

    fun saveWorkspace(context: Context, serverUrl: String, workspaceId: String, ownerToken: String, deviceId: String, deviceToken: String, deviceName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SERVER, normalizeServer(serverUrl))
            .putString(WORKSPACE, workspaceId)
            .putString(OWNER_TOKEN, ownerToken)
            .putString(DEVICE_ID, deviceId)
            .putString(DEVICE_TOKEN, deviceToken)
            .putString(DEVICE_NAME, deviceName.trim().ifBlank { defaultDeviceName() })
            .apply()
    }

    fun setServerUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SERVER, normalizeServer(value)).apply()
    }

    fun setDeviceName(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DEVICE_NAME, value.trim().ifBlank { defaultDeviceName() }).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun isConfigured(context: Context): Boolean = serverUrl(context).isNotBlank() && deviceToken(context).isNotBlank() && deviceId(context).isNotBlank()
    fun isOwner(context: Context): Boolean = isConfigured(context) && ownerToken(context).isNotBlank()

    fun normalizeServer(value: String): String {
        var v = value.trim().trimEnd('/')
        if (v.isNotBlank() && !v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
        return v
    }

    fun defaultDeviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android" }
}

data class CloudDevice(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val lastSeen: Long,
    val online: Boolean
)

data class CloudState(
    val active: Boolean,
    val until: Long,
    val intention: String,
    val updatedAt: Long,
    val sourceDeviceId: String,
    val categories: Set<WebsiteCategory>,
    val customDomains: Set<String>,
    val profile: String,
    val strictMode: String,
    val sessionId: String,
    val devices: List<CloudDevice> = emptyList()
)

data class CloudWorkspaceResult(
    val workspaceId: String,
    val ownerToken: String,
    val deviceId: String,
    val deviceToken: String
)

data class CloudPairCode(val code: String, val expiresAt: Long)

object CloudSyncClient {
    const val APP_VERSION = "0.4.0-alpha1"

    fun health(serverUrl: String): Result<Boolean> = runCatching {
        val conn = connection(CloudSyncPreferences.normalizeServer(serverUrl), "/health", "GET", null)
        conn.useJsonResponse { it.optBoolean("ok", false) }
    }

    fun createWorkspace(serverUrl: String, deviceName: String): Result<CloudWorkspaceResult> = runCatching {
        val body = JSONObject().apply {
            put("deviceName", deviceName.trim().ifBlank { CloudSyncPreferences.defaultDeviceName() })
            put("platform", "android")
            put("appVersion", APP_VERSION)
        }
        val conn = connection(CloudSyncPreferences.normalizeServer(serverUrl), "/v1/workspaces", "POST", null)
        conn.writeJson(body)
        conn.useJsonResponse {
            CloudWorkspaceResult(
                workspaceId = it.getString("workspaceId"),
                ownerToken = it.getString("ownerToken"),
                deviceId = it.getString("deviceId"),
                deviceToken = it.getString("deviceToken")
            )
        }
    }

    fun generatePairCode(context: Context): Result<CloudPairCode> = runCatching {
        require(CloudSyncPreferences.isOwner(context)) { "This phone is not the workspace owner" }
        val conn = connection(
            CloudSyncPreferences.serverUrl(context),
            "/v1/pair-codes",
            "POST",
            CloudSyncPreferences.ownerToken(context)
        )
        conn.writeJson(JSONObject())
        conn.useJsonResponse { CloudPairCode(it.getString("code"), it.getLong("expiresAt")) }
    }

    fun fetchState(context: Context): Result<CloudState> = runCatching {
        require(CloudSyncPreferences.isConfigured(context)) { "Cloud sync is not configured" }
        val conn = connection(
            CloudSyncPreferences.serverUrl(context),
            "/v1/state",
            "GET",
            CloudSyncPreferences.deviceToken(context)
        )
        conn.useJsonResponse(::parseState)
    }

    fun pushLocalState(context: Context): Result<CloudState> = runCatching {
        require(CloudSyncPreferences.isConfigured(context)) { "Cloud sync is not configured" }
        val body = JSONObject().apply {
            put("active", FocusPreferences.isActive(context))
            put("until", FocusPreferences.until(context))
            put("intention", FocusPreferences.intention(context))
            put("updatedAt", FocusPreferences.updatedAt(context))
            put("categories", JSONArray(WebsitePreferences.categories(context).map { it.name }))
            put("customDomains", JSONArray(WebsitePreferences.customDomains(context).toList()))
            put("profile", FocusPreferences.profile(context))
            put("strictMode", FocusPreferences.strictMode(context))
            put("sessionId", FocusPreferences.sessionId(context))
        }
        val conn = connection(
            CloudSyncPreferences.serverUrl(context),
            "/v1/state",
            "PUT",
            CloudSyncPreferences.deviceToken(context)
        )
        conn.writeJson(body)
        conn.useJsonResponse(::parseState)
    }

    fun heartbeat(context: Context): Result<Unit> = runCatching {
        val body = JSONObject().apply {
            put("deviceName", CloudSyncPreferences.deviceName(context))
            put("appVersion", APP_VERSION)
        }
        val conn = connection(
            CloudSyncPreferences.serverUrl(context),
            "/v1/heartbeat",
            "POST",
            CloudSyncPreferences.deviceToken(context)
        )
        conn.writeJson(body)
        conn.useJsonResponse { Unit }
    }

    fun logEvent(context: Context, type: String, value: String = "", metadata: JSONObject = JSONObject()): Result<Unit> = runCatching {
        if (!CloudSyncPreferences.isConfigured(context)) return@runCatching Unit
        val body = JSONObject().apply {
            put("type", type)
            put("value", value)
            put("metadata", metadata)
            put("occurredAt", System.currentTimeMillis())
            put("deviceName", CloudSyncPreferences.deviceName(context))
            put("appVersion", APP_VERSION)
        }
        val conn = connection(
            CloudSyncPreferences.serverUrl(context),
            "/v1/events",
            "POST",
            CloudSyncPreferences.deviceToken(context)
        )
        conn.writeJson(body)
        conn.useJsonResponse { Unit }
    }

    fun applyIfNewer(context: Context, remote: CloudState): Boolean {
        if (remote.updatedAt <= FocusPreferences.updatedAt(context)) return false
        if (remote.active && remote.until > System.currentTimeMillis()) {
            FocusPreferences.startUntil(
                context = context,
                until = remote.until,
                intention = remote.intention,
                updatedAt = remote.updatedAt,
                profile = remote.profile,
                strictMode = remote.strictMode,
                sessionId = remote.sessionId
            )
        } else {
            FocusPreferences.stop(context, remote.updatedAt)
        }
        WebsitePreferences.setCategories(context, remote.categories)
        WebsitePreferences.setCustomDomains(context, remote.customDomains)
        return true
    }

    private fun parseState(json: JSONObject): CloudState {
        val categoriesJson = json.optJSONArray("categories") ?: JSONArray()
        val categories = buildSet {
            for (i in 0 until categoriesJson.length()) {
                runCatching { WebsiteCategory.valueOf(categoriesJson.getString(i)) }.getOrNull()?.let(::add)
            }
        }
        val customJson = json.optJSONArray("customDomains") ?: JSONArray()
        val custom = buildSet {
            for (i in 0 until customJson.length()) add(customJson.optString(i))
        }.filter { it.isNotBlank() }.toSet()
        val devicesJson = json.optJSONArray("devices") ?: JSONArray()
        val devices = buildList {
            for (i in 0 until devicesJson.length()) {
                val d = devicesJson.optJSONObject(i) ?: continue
                add(
                    CloudDevice(
                        id = d.optString("id"),
                        name = d.optString("name", "Device"),
                        platform = d.optString("platform", "unknown"),
                        appVersion = d.optString("appVersion", ""),
                        lastSeen = d.optLong("lastSeen", 0L),
                        online = d.optBoolean("online", false)
                    )
                )
            }
        }
        return CloudState(
            active = json.optBoolean("active", false),
            until = json.optLong("until", 0L),
            intention = json.optString("intention", ""),
            updatedAt = json.optLong("updatedAt", 0L),
            sourceDeviceId = json.optString("sourceDeviceId", ""),
            categories = categories,
            customDomains = custom,
            profile = json.optString("profile", "Work"),
            strictMode = json.optString("strictMode", "NORMAL"),
            sessionId = json.optString("sessionId", ""),
            devices = devices
        )
    }

    private fun connection(server: String, path: String, method: String, bearer: String?): HttpURLConnection {
        require(server.isNotBlank()) { "Server URL is empty" }
        val conn = URL(server.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3500
        conn.readTimeout = 3500
        conn.setRequestProperty("Accept", "application/json")
        if (!bearer.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer ${bearer.trim()}")
        return conn
    }

    private fun HttpURLConnection.writeJson(body: JSONObject) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }

    private inline fun <T> HttpURLConnection.useJsonResponse(block: (JSONObject) -> T): T {
        try {
            val status = responseCode
            val source = if (status in 200..299) inputStream else errorStream
            val text = source?.use { input -> BufferedReader(InputStreamReader(input)).readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw IllegalStateException(if (detail.isBlank()) "Sync server returned HTTP $status" else detail)
            }
            return block(if (text.isBlank()) JSONObject() else JSONObject(text))
        } finally {
            disconnect()
        }
    }
}
