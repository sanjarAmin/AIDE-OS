package com.osamu.aide.core.common

/**
 * Explicit success/failure type for operations that fail as a matter of course
 * -- compiling, resolving dependencies, reading a file the user just deleted.
 * Preferred over exceptions on module boundaries so callers cannot ignore them.
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

data class AppError(
    val message: String,
    val cause: Throwable? = null,
)

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value

/** Runs [block], converting any thrown exception into an [AppResult.Failure]. */
inline fun <T> runCatchingResult(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (t: Throwable) {
    AppResult.Failure(AppError(t.message ?: t::class.java.simpleName, t))
}
