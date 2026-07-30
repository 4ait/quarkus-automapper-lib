package ru.code4a.quarkus.automapper.services

/**
 * Metadata boundary used by Arc to initialize one CDI-dependent mapper independently from all
 * other mappers. Runtime implementations are normal-scoped synthetic beans and therefore lazy.
 */
fun interface AutoMapInputClassInfoProvider {
  fun get(): Any
}

internal fun fixedInputClassInfoProvider(
  inputClassInfo: ru.code4a.quarkus.automapper.meta.InputClassInfo,
): AutoMapInputClassInfoProvider = AutoMapInputClassInfoProvider { inputClassInfo }
