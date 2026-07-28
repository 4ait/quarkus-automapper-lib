package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.utils.ArcService
import kotlin.reflect.KClass

/** A typed key for sharing an application value within one mapping operation. */
class AutoMapContextKey<VALUE : Any>(
  val name: String,
)

/** Strongly typed context exposed to a single-item existing-entity lookup. */
class AutoMapExistingEntityLookupContext<
  INPUT : Any,
  TARGET : Any,
  PARENT_SOURCE : Any,
  PARENT_TARGET : Any,
  > internal constructor(
  val source: INPUT,
  val targetKClass: KClass<TARGET>,
  val parentSource: PARENT_SOURCE?,
  val parentTarget: PARENT_TARGET?,
  private val operation: AutoMapOperationContext,
) {
  fun <VALUE : Any> put(key: AutoMapContextKey<VALUE>, value: VALUE) {
    operation.values[key] = value
  }

  fun <VALUE : Any> get(key: AutoMapContextKey<VALUE>): VALUE? {
    @Suppress("UNCHECKED_CAST")
    return operation.values[key] as VALUE?
  }

  fun <SERVICE : Any> getService(serviceClass: KClass<SERVICE>): SERVICE {
    return ArcService.getFromClass(serviceClass.java)
  }
}

/** Strongly typed context exposed to a batch existing-entity lookup. */
class AutoMapBatchExistingEntityLookupContext<
  INPUT : Any,
  TARGET : Any,
  PARENT_SOURCE : Any,
  PARENT_TARGET : Any,
  > internal constructor(
  val sources: List<INPUT>,
  val targetKClass: KClass<TARGET>,
  val parentSource: PARENT_SOURCE?,
  val parentTarget: PARENT_TARGET?,
  private val operation: AutoMapOperationContext,
) {
  fun <VALUE : Any> put(key: AutoMapContextKey<VALUE>, value: VALUE) {
    operation.values[key] = value
  }

  fun <VALUE : Any> get(key: AutoMapContextKey<VALUE>): VALUE? {
    @Suppress("UNCHECKED_CAST")
    return operation.values[key] as VALUE?
  }

  fun <SERVICE : Any> getService(serviceClass: KClass<SERVICE>): SERVICE {
    return ArcService.getFromClass(serviceClass.java)
  }
}

internal class AutoMapMappingFrame(
  val source: Any,
  val targetKClass: KClass<*>,
  val parent: AutoMapMappingFrame?,
  val operation: AutoMapOperationContext,
) {
  var target: Any? = null
}

internal class AutoMapOperationContext {
  val lookupCache = mutableMapOf<AutoMapLookupCacheKey, Any?>()
  val sourceLookupKeys = mutableMapOf<AutoMapSourceLookupKey, Any?>()
  val values = mutableMapOf<AutoMapContextKey<*>, Any>()
}

internal class AutoMapIdentityKey(
  private val value: Any?,
) {
  override fun equals(other: Any?): Boolean {
    return other is AutoMapIdentityKey && value === other.value
  }

  override fun hashCode(): Int = value?.let(System::identityHashCode) ?: 0
}

internal data class AutoMapLookupCacheKey(
  val strategyClass: KClass<*>,
  val targetKClass: KClass<*>,
  val parentTarget: AutoMapIdentityKey,
  val lookupKey: Any,
)

internal data class AutoMapSourceLookupKey(
  val strategyClass: KClass<*>,
  val parentTarget: AutoMapIdentityKey,
  val source: AutoMapIdentityKey,
)
