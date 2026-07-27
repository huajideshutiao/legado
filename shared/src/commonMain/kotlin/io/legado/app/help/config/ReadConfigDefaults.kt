package io.legado.app.help.config

import io.legado.app.utils.KS_JSON

/**
 * 内置阅读样式主题 (对应 app 端 `DefaultData.readConfigs` 读 assets/defaultData/readConfig.json)。
 *
 * app 端从 assets 读文件, 非 Android 端无 assets, 故把同一份 JSON 原样内联为常量,
 * 由 [readConfigs] 解码为 [ReadStyleConfig] 列表 (未出现的字段走 data class 默认值)。
 */
object ReadConfigDefaults {

    /** 默认样式主题列表 (6 个: 微信读书 + 预设 1..5), 解码失败返回单个默认主题。 */
    val readConfigs: List<ReadStyleConfig> by lazy {
        runCatching { KS_JSON.decodeFromString<List<ReadStyleConfig>>(DEFAULT_READ_CONFIG_JSON) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: listOf(ReadStyleConfig())
    }

    /** 与 origin/quickjs `app/src/main/assets/defaultData/readConfig.json` 逐字节一致。 */
    private const val DEFAULT_READ_CONFIG_JSON = """
[
  {
    "bgStr": "#ffc0edc6",
    "bgStrEInk": "#FFFFFF",
    "bgStrNight": "#000000",
    "bgType": 0,
    "bgTypeEInk": 0,
    "bgTypeNight": 0,
    "darkStatusIcon": true,
    "darkStatusIconEInk": true,
    "darkStatusIconNight": false,
    "footerMode": 0,
    "footerPaddingBottom": 10,
    "footerPaddingLeft": 13,
    "footerPaddingRight": 17,
    "footerPaddingTop": 0,
    "headerMode": 0,
    "headerPaddingBottom": 0,
    "headerPaddingLeft": 19,
    "headerPaddingRight": 16,
    "headerPaddingTop": 10,
    "letterSpacing": 0,
    "lineSpacingExtra": 10,
    "name": "微信读书",
    "paddingBottom": 4,
    "paddingLeft": 22,
    "paddingRight": 22,
    "paddingTop": 5,
    "paragraphIndent": "　　",
    "paragraphSpacing": 6,
    "showFooterLine": true,
    "showHeaderLine": true,
    "textBold": 0,
    "textColor": "#ff0b0b0b",
    "textColorEInk": "#000000",
    "textColorNight": "#ADADAD",
    "textSize": 24,
    "tipColor": -10461088,
    "tipFooterLeft": 7,
    "tipFooterMiddle": 0,
    "tipFooterRight": 6,
    "tipHeaderLeft": 1,
    "tipHeaderMiddle": 0,
    "tipHeaderRight": 2,
    "titleBottomSpacing": 0,
    "titleMode": 0,
    "titleSize": 4,
    "titleTopSpacing": 0
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
