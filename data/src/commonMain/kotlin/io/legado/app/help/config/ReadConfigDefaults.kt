package io.legado.app.help.config

import io.legado.app.help.config.ReadConfigDefaults.readConfigs
import io.legado.app.utils.KS_JSON

/**
 * 内置阅读样式主题 (对应 app 端 `DefaultData.readConfigs` 读 assets/defaultData/readConfig.json)。
 *
 * app 端从 assets 读文件, 非 Android 端无 assets, 故把同一份 JSON 原样内联为常量,
 * 由 [readConfigs] 解码为 [ReadStyleConfig] 列表 (未出现的字段走 data class 默认值)。
 */
object ReadConfigDefaults {

    /** 默认样式主题列表 (6 个: 微信读书 + 预设 1..5)，内置常量是唯一来源。 */
    val readConfigs: List<ReadStyleConfig> by lazy {
        KS_JSON.decodeFromString<List<ReadStyleConfig>>(DEFAULT_READ_CONFIG_JSON).also {
            require(it.isNotEmpty()) { "Default read style configuration is empty" }
        }
    }

    /** 新增样式明确复制当前样式，避免把数据类兜底默认值当作业务模板。 */
    fun newStyleFrom(current: ReadStyleConfig): ReadStyleConfig =
        current.copy(name = "", textColor = 0, bgMeanColor = 0)

    /**
     * 字段集合与原版 `app/src/main/assets/defaultData/readConfig.json` 一致。
     *
     * 首项「微信读书」已内聚为 [ReadStyleConfig] 的字段默认值，故只保留 `name`：
     * 原版在字段默认值与此 JSON 各存一套排版参数，而真正生效的始终是本首项，
     * 字面默认值只在兜底路径（配置文件损坍 / 解码失败 / `resetAll` 补齐）露出来，
     * 两套不一致就是缺陷。其余字段与默认值重合即冗余，不再重写。
     *
     * 预设 1..5 只换配色（背景 / 文字色 / 状态栏图标明暗），排版参数全部继承默认值。
     */
    private const val DEFAULT_READ_CONFIG_JSON = """
[
  {
    "name": "微信读书"
  },
  {
    "name": "预设1",
    "bgStr": "#FFFFFF",
    "bgStrNight": "#000000",
    "textColor": "#000000",
    "textColorNight": "#FFFFFF",
    "bgType": 0,
    "bgTypeNight": 0,
    "darkStatusIcon": true,
    "darkStatusIconNight": false
  },
  {
    "name": "预设2",
    "bgStr": "#DDC090",
    "bgStrNight": "#3C3F43",
    "textColor": "#3E3422",
    "textColorNight": "#DCDFE1",
    "bgType": 0,
    "bgTypeNight": 0,
    "darkStatusIcon": true,
    "darkStatusIconNight": false
  },
  {
    "name": "预设3",
    "bgStr": "#C2D8AA",
    "bgStrNight": "#3C3F43",
    "textColor": "#596C44",
    "textColorNight": "#88C16F",
    "bgType": 0,
    "bgTypeNight": 0,
    "darkStatusIcon": false,
    "darkStatusIconNight": false
  },
  {
    "name": "预设4",
    "bgStr": "#DBB8E2",
    "bgStrNight": "#3C3F43",
    "textColor": "#68516C",
    "textColorNight": "#F6AEAE",
    "bgType": 0,
    "bgTypeNight": 0,
    "darkStatusIcon": false,
    "darkStatusIconNight": false
  },
  {
    "name": "预设5",
    "bgStr": "#ABCEE0",
    "bgStrNight": "#3C3F43",
    "textColor": "#3D4C54",
    "textColorNight": "#90BFF5",
    "bgType": 0,
    "bgTypeNight": 0,
    "darkStatusIcon": false,
    "darkStatusIconNight": false
  }
]
"""
}
