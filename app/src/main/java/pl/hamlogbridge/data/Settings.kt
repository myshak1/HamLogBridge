package pl.hamlogbridge.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("hamlogbridge")

data class TargetCfg(
    val enabled: Boolean = false,
    val params: Map<String, String> = emptyMap()
)

data class AppSettings(
    val udpPort: Int = 2237,
    val multicastGroup: String = "",
    val relayTargets: String = "",
    val autoStart: Boolean = false,
    val logDecodes: Boolean = true,
    val myCall: String = "",
    val myGrid: String = "",
    val operator: String = "",
    val txPowerW: String = "",
    val rigName: String = "Xiegu X6100",
    val antenna: String = "",
    val connectionMode: String = MODE_WIFI,
    val btDeviceAddress: String = "",
    val btDeviceName: String = "",
    val targets: Map<String, TargetCfg> = emptyMap()
) {
    fun cfg(id: String): TargetCfg = targets[id] ?: TargetCfg()
    fun enabledTargetIds(): List<String> = targets.filterValues { it.enabled }.keys.toList()

    companion object {
        const val MODE_WIFI = "WIFI"
        const val MODE_BLUETOOTH = "BLUETOOTH"
        const val MODE_BOTH = "BOTH"
    }
}

class SettingsStore(private val ctx: Context) {

    private val KEY = stringPreferencesKey("settings_json")

    val flow: Flow<AppSettings> = ctx.dataStore.data.map { prefs ->
        prefs[KEY]?.let { runCatching { fromJson(it) }.getOrNull() } ?: AppSettings()
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        ctx.dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { runCatching { fromJson(it) }.getOrNull() } ?: AppSettings()
            prefs[KEY] = toJson(block(current))
        }
    }

    companion object {
        fun toJson(s: AppSettings): String {
            val targets = JSONObject()
            s.targets.forEach { (id, cfg) ->
                val params = JSONObject()
                cfg.params.forEach { (k, v) -> params.put(k, v) }
                targets.put(id, JSONObject().put("enabled", cfg.enabled).put("params", params))
            }
            return JSONObject()
                .put("udpPort", s.udpPort)
                .put("multicastGroup", s.multicastGroup)
                .put("relayTargets", s.relayTargets)
                .put("autoStart", s.autoStart)
                .put("logDecodes", s.logDecodes)
                .put("myCall", s.myCall)
                .put("myGrid", s.myGrid)
                .put("operator", s.operator)
                .put("txPowerW", s.txPowerW)
                .put("rigName", s.rigName)
                .put("antenna", s.antenna)
                .put("connectionMode", s.connectionMode)
                .put("btDeviceAddress", s.btDeviceAddress)
                .put("btDeviceName", s.btDeviceName)
                .put("targets", targets)
                .toString()
        }

        fun fromJson(json: String): AppSettings {
            val o = JSONObject(json)
            val targets = LinkedHashMap<String, TargetCfg>()
            o.optJSONObject("targets")?.let { t ->
                t.keys().forEach { id ->
                    val e = t.getJSONObject(id)
                    val params = LinkedHashMap<String, String>()
                    e.optJSONObject("params")?.let { p ->
                        p.keys().forEach { k -> params[k] = p.optString(k, "") }
                    }
                    targets[id] = TargetCfg(e.optBoolean("enabled", false), params)
                }
            }
            return AppSettings(
                udpPort = o.optInt("udpPort", 2237).takeIf { it in 1..65535 } ?: 2237,
                multicastGroup = o.optString("multicastGroup", ""),
                relayTargets = o.optString("relayTargets", ""),
                autoStart = o.optBoolean("autoStart", false),
                logDecodes = o.optBoolean("logDecodes", true),
                myCall = o.optString("myCall", ""),
                myGrid = o.optString("myGrid", ""),
                operator = o.optString("operator", ""),
                txPowerW = o.optString("txPowerW", ""),
                rigName = o.optString("rigName", "Xiegu X6100"),
                antenna = o.optString("antenna", ""),
                connectionMode = o.optString("connectionMode", AppSettings.MODE_WIFI),
                btDeviceAddress = o.optString("btDeviceAddress", ""),
                btDeviceName = o.optString("btDeviceName", ""),
                targets = targets
            )
        }
    }
}
