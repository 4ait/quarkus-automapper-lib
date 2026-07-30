package ru.code4a.quarkus.automapper.meta

import ru.code4a.quarkus.automapper.services.AutoMapMappingFrame
import kotlin.reflect.KClass
import kotlin.reflect.KType

internal abstract class ExistingEntityLocatorInfo(
  val locatorClass: KClass<*>,
  val parentSourceType: KType?,
  val parentTargetType: KType?,
) {
  abstract fun getLookupKey(input: Any, context: AutoMapMappingFrame): Any?

  abstract fun findExisting(input: Any, lookupKey: Any, context: AutoMapMappingFrame): Any?

  abstract fun validateExisting(target: Any, input: Any, context: AutoMapMappingFrame)
}

internal class BatchExistingEntityLocatorInfo(
  locatorClass: KClass<*>,
  parentSourceType: KType?,
  parentTargetType: KType?,
  private val keyGetter: (Any, AutoMapMappingFrame) -> Any?,
  private val loader: (Set<Any>, List<Any>, AutoMapMappingFrame) -> Map<Any, Any>,
  private val validator: (Any, Any, AutoMapMappingFrame) -> Unit,
) : ExistingEntityLocatorInfo(locatorClass, parentSourceType, parentTargetType) {
  override fun getLookupKey(input: Any, context: AutoMapMappingFrame): Any? {
    return keyGetter(input, context)
  }

  override fun findExisting(input: Any, lookupKey: Any, context: AutoMapMappingFrame): Any? {
    return loader(setOf(lookupKey), listOf(input), context)[lookupKey]
  }

  fun loadExisting(
    keys: Set<Any>,
    inputs: List<Any>,
    context: AutoMapMappingFrame,
  ): Map<Any, Any> {
    return loader(keys, inputs, context)
  }

  override fun validateExisting(target: Any, input: Any, context: AutoMapMappingFrame) {
    validator(target, input, context)
  }
}
