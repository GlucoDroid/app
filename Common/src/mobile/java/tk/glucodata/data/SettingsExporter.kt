package tk.glucodata.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.Natives
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

object SettingsExporter {
    private const val TAG = "SettingsExporter"
    private const val SCHEMA = "tk.glucodata.settings-export"
    private const val SCHEMA_VERSION = 1

    private val nativeSettingsFiles = listOf(
        "settings.dat",
        "backup.dat",
        "orbackup.dat"
    )

    data class ImportSummary(
        val sharedPreferenceFiles: Int,
        val preferenceValues: Int,
        val nativeFiles: Int
    )

    suspend fun exportToJson(context: Context, uri: Uri): Result<Unit> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildPayload(appContext)
                val outputStream = appContext.contentResolver.openOutputStream(uri)
                    ?: error("Could not open export destination")
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload.toString(2))
                    writer.write("\n")
                }
                Log.i(TAG, "Exported settings package")
                Unit
            }.onFailure {
                Log.e(TAG, "Settings export failed", it)
            }
        }
    }

    suspend fun isSettingsExport(context: Context, uri: Uri): Boolean {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                readPayload(appContext, uri).optString("schema") == SCHEMA
            }.getOrDefault(false)
        }
    }

    suspend fun importFromJson(context: Context, uri: Uri): Result<ImportSummary> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = readPayload(appContext, uri)
                require(payload.optString("schema") == SCHEMA) { "Unsupported settings export" }
                val schemaVersion = payload.optInt("schemaVersion", 0)
                require(schemaVersion in 1..SCHEMA_VERSION) {
                    "Unsupported settings export version: $schemaVersion"
                }

                val preferencesSummary = importSharedPreferences(
                    appContext,
                    payload.optJSONObject("sharedPreferences") ?: JSONObject()
                )
                val nativeFileCount = importNativeSettingsFiles(
                    appContext,
                    payload.optJSONObject("nativeSettingsFiles") ?: JSONObject()
                )

                ImportSummary(
                    sharedPreferenceFiles = preferencesSummary.first,
                    preferenceValues = preferencesSummary.second,
                    nativeFiles = nativeFileCount
                )
            }.onFailure {
                Log.e(TAG, "Settings import failed", it)
            }
        }
    }

    private fun buildPayload(context: Context): JSONObject {
        return JSONObject()
            .put("schema", SCHEMA)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("containsSensitiveData", true)
            .put("app", buildAppInfo(context))
            .put("sharedPreferences", buildSharedPreferences(context))
            .put("nativeSettingsFiles", buildNativeSettingsFiles(context))
            .put("nativeTransferSettings", buildNativeTransferSettings())
    }

    private fun buildAppInfo(context: Context): JSONObject {
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("locale", Locale.getDefault().toLanguageTag())
    }

    private fun buildSharedPreferences(context: Context): JSONObject {
        val result = JSONObject()
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefFiles = prefsDir
            .listFiles { file -> file.isFile && file.extension == "xml" }
            ?.sortedBy { it.name }
            .orEmpty()

        prefFiles.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .all
                .toSortedMap()
                .forEach { (key, value) ->
                    values.put(key, preferenceEntry(value))
                }

            result.put(
                name,
                JSONObject()
                    .put("byteCount", file.length())
                    .put("lastModifiedEpochMillis", file.lastModified())
                    .put("values", values)
                    .put("rawXmlBase64", encodeFile(file))
            )
        }
        return result
    }

    private fun importSharedPreferences(
        context: Context,
        exportedPreferences: JSONObject
    ): Pair<Int, Int> {
        val importedNames = exportedPreferences.keySet()
        discoverSharedPreferenceNames(context)
            .filterNot { it in importedNames }
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }

        var importedValueCount = 0
        importedNames.sorted().forEach { name ->
            val entry = exportedPreferences.optJSONObject(name) ?: return@forEach
            val values = entry.optJSONObject("values") ?: JSONObject()
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()

            values.keySet().sorted().forEach { key ->
                putPreferenceValue(editor, key, values.getJSONObject(key))
                importedValueCount++
            }

            require(editor.commit()) { "Could not import preferences: $name" }
        }

        return importedNames.size to importedValueCount
    }

    private fun putPreferenceValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        entry: JSONObject
    ) {
        when (entry.optString("type")) {
            "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
            "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
            "int" -> editor.putInt(key, entry.getInt("value"))
            "long" -> editor.putLong(key, entry.getLong("value"))
            "string" -> editor.putString(
                key,
                if (entry.isNull("value")) null else entry.getString("value")
            )
            "string_set" -> {
                val values = entry.optJSONArray("value") ?: JSONArray()
                editor.putStringSet(
                    key,
                    buildSet {
                        for (index in 0 until values.length()) {
                            add(values.getString(index))
                        }
                    }
                )
            }
            "null" -> editor.remove(key)
            else -> throw IllegalArgumentException("Unsupported preference type for $key")
        }
    }

    private fun preferenceEntry(value: Any?): JSONObject {
        return JSONObject()
            .put("type", preferenceType(value))
            .put("value", preferenceValue(value))
    }

    private fun preferenceType(value: Any?): String {
        return when (value) {
            is Boolean -> "boolean"
            is Float -> "float"
            is Int -> "int"
            is Long -> "long"
            is String -> "string"
            is Set<*> -> "string_set"
            null -> "null"
            else -> value.javaClass.name
        }
    }

    private fun preferenceValue(value: Any?): Any {
        return when (value) {
            is Float -> value.toDouble()
            is Set<*> -> JSONArray(value.filterIsInstance<String>().sorted())
            null -> JSONObject.NULL
            else -> value
        }
    }

    private fun buildNativeSettingsFiles(context: Context): JSONObject {
        val result = JSONObject()
        nativeSettingsFiles.forEach { name ->
            val file = File(context.filesDir, name)
            if (file.isFile) {
                result.put(
                    name,
                    JSONObject()
                        .put("byteCount", file.length())
                        .put("lastModifiedEpochMillis", file.lastModified())
                        .put("base64", encodeFile(file))
                )
            }
        }
        return result
    }

    private fun importNativeSettingsFiles(
        context: Context,
        exportedFiles: JSONObject
    ): Int {
        var importedCount = 0
        nativeSettingsFiles.forEach { name ->
            val entry = exportedFiles.optJSONObject(name) ?: return@forEach
            val encoded = entry.optString("base64").takeIf { it.isNotBlank() } ?: return@forEach
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            File(context.filesDir, name).outputStream().use { output ->
                output.write(bytes)
            }
            importedCount++
        }
        return importedCount
    }

    private fun buildNativeTransferSettings(): JSONObject {
        return JSONObject().also { result ->
            runCatching { Natives.bytesettings() }
                .getOrNull()
                ?.let { bytes ->
                    result.put("byteCount", bytes.size)
                    result.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
        }
    }

    private fun encodeFile(file: File): String {
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    private fun readPayload(context: Context, uri: Uri): JSONObject {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open import source")
        val text = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }

    private fun discoverSharedPreferenceNames(context: Context): Set<String> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        return prefsDir
            .listFiles { file -> file.isFile && file.extension == "xml" }
            ?.mapTo(mutableSetOf()) { it.name.removeSuffix(".xml") }
            .orEmpty()
    }

    private fun JSONObject.keySet(): Set<String> {
        val keys = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            keys.add(iterator.next())
        }
        return keys
    }
}
