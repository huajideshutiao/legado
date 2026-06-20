package io.legado.app.help.config

import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal class PrefDelegate<T>(
    private val getter: () -> T,
    private val setter: (T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = getter()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = setter(value)
}

internal fun boolPref(key: String, default: Boolean = false) = PrefDelegate(
    { appCtx.getPrefBoolean(key, default) },
    { appCtx.putPrefBoolean(key, it) },
)

internal fun intPref(key: String, default: Int = 0, range: IntRange? = null) = PrefDelegate(
    {
        val v = appCtx.getPrefInt(key, default)
        if (range == null) v else v.coerceIn(range)
    },
    { appCtx.putPrefInt(key, if (range == null) it else it.coerceIn(range)) },
)

internal fun longPref(key: String, default: Long = 0L) = PrefDelegate(
    { appCtx.getPrefLong(key, default) },
    { appCtx.putPrefLong(key, it) },
)

internal fun stringPref(key: String, default: String? = null) = PrefDelegate<String?>(
    { appCtx.getPrefString(key, default) },
    { appCtx.putPrefString(key, it) },
)

internal fun nonNullStringPref(key: String, default: String) = PrefDelegate(
    { appCtx.getPrefString(key) ?: default },
    { appCtx.putPrefString(key, it) },
)

internal fun stringPrefClearOnEmpty(key: String) = PrefDelegate<String?>(
    { appCtx.getPrefString(key) },
    { if (it.isNullOrEmpty()) appCtx.removePref(key) else appCtx.putPrefString(key, it) },
)

private val cachedReloaders = HashMap<String, () -> Unit>()

internal fun reloadCachedPref(key: String) {
    cachedReloaders[key]?.invoke()
}

internal class CachedPref<T>(
    key: String,
    private val load: () -> T,
    private val store: (T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    @Volatile
    private var current: T = load()

    init {
        cachedReloaders[key] = { current = load() }
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = current
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        store(value)
        current = value
    }
}

internal fun cachedBoolPref(key: String, default: Boolean = false) = CachedPref(
    key,
    { appCtx.getPrefBoolean(key, default) },
    { appCtx.putPrefBoolean(key, it) },
)

internal fun cachedIntPref(key: String, default: Int = 0) = CachedPref(
    key,
    { appCtx.getPrefInt(key, default) },
    { appCtx.putPrefInt(key, it) },
)

internal fun cachedStringPref(key: String, default: String? = null) = CachedPref(
    key,
    { appCtx.getPrefString(key, default) },
    { appCtx.putPrefString(key, it) },
)

internal fun <T> cachedPref(key: String, load: () -> T, store: (T) -> Unit) =
    CachedPref(key, load, store)
