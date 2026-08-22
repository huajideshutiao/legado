package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "replace_rules",
    indices = [(Index(value = ["id"]))]
)
data class ReplaceRule(
    @PrimaryKey(autoGenerate = true)
    var id: Long = systemCurrentTimeMillis(),
    //名称
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    //分组
    var group: String? = null,
    //替换内容
    @ColumnInfo(defaultValue = "")
    var pattern: String = "",
    //替换为
    @ColumnInfo(defaultValue = "")
    var replacement: String = "",
    //作用范围
    var scope: String? = null,
    //作用于标题
    @ColumnInfo(defaultValue = "0")
    var scopeTitle: Boolean = false,
    //作用于正文
    @ColumnInfo(defaultValue = "1")
    var scopeContent: Boolean = true,
    //排除范围
    var excludeScope: String? = null,
    //是否启用
    @ColumnInfo(defaultValue = "1")
    var isEnabled: Boolean = true,
    //是否正则
    @ColumnInfo(defaultValue = "1")
    var isRegex: Boolean = true,
    //超时时间
    @ColumnInfo(defaultValue = "3000")
    var timeoutMillisecond: Long = 3000L,
    //排序
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var order: Int = Int.MIN_VALUE
) {

    // 不覆写 equals/hashCode: 只比 id 会让规则列表/编辑表单把"同一条改了内容"判为相同而吞掉更新;
    // 字段是 var 且多处原地改, 结构 hashCode 会漂移, 故本类实例禁止作 HashSet 元素 / HashMap key

    @delegate:Transient
    @delegate:Ignore
    val regex: Regex by lazy {
        pattern.toRegex()
    }

    fun getDisplayNameGroup(): String {
        return if (group.isNullOrBlank()) {
            name
        } else {
            // String.format 改字符串模板，避免 commonMain 引入 java.lang.String.format 平台依赖
            "$name ($group)"
        }
    }

    fun isValid(): Boolean {
        if (pattern.isEmpty()) {
            return false
        }
        //判断正则表达式是否正确
        if (isRegex) {
            try {
                // pattern.toRegex() 内部走 Pattern.compile，行为一致；
                // commonMain 不能引用 java.util.regex.PatternSyntaxException，故 catch Exception
                pattern.toRegex()
            } catch (ex: Exception) {
                AppLog.put("正则语法错误或不支持：${ex.message}", ex)
                return false
            }
            // Pattern.compile测试通过，但是部分情况下会替换超时，报错，一般发生在修改表达式时漏删了
            if (pattern.endsWith('|') && !pattern.endsWith("\\|")) {
                return false
            }
        }
        return true
    }

    @Throws(NoStackTraceException::class)
    fun checkValid() {
        if (!isValid()) {
            throw NoStackTraceException(appString(AppStringKey.replace_rule_invalid))
        }
    }

    fun getValidTimeoutMillisecond(): Long {
        if (timeoutMillisecond <= 0) {
            return 3000L
        }
        return timeoutMillisecond
    }
}
