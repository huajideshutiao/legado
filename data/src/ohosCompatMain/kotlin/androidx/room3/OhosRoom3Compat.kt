package androidx.room3

import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ColumnTypeConverter

@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
    AnnotationTarget.CLASS,
)
@Retention(AnnotationRetention.BINARY)
public annotation class ColumnTypeConverters(
    vararg val value: KClass<*> = [],
    val builtInColumnTypeConverters: BuiltInColumnTypeConverters = BuiltInColumnTypeConverters(),
)

@Target(allowedTargets = [])
@Retention(AnnotationRetention.BINARY)
public annotation class BuiltInColumnTypeConverters(
    val enums: State = State.INHERITED,
    val uuid: State = State.INHERITED,
    val byteBuffer: State = State.INHERITED,
) {
    public enum class State {
        ENABLED,
        DISABLED,
        INHERITED,
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class ProvidedColumnTypeConverter

/** Executes a single SQL statement that returns no values. */
public suspend fun PooledConnection.executeSQL(sql: String) {
    usePrepared(sql) { it.step() }
}
