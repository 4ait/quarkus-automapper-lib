package ru.code4a.quarkus.automapper.interfaces

import ru.code4a.quarkus.automapper.services.AutoMapBatchExistingEntityLookupContext
import ru.code4a.quarkus.automapper.services.AutoMapExistingEntityLookupContext

/** Marker interface for statically introspected existing-target strategies. */
interface AutoMapExistingEntityLocator<
  INPUT : Any,
  TARGET : Any,
  KEY : Any,
  PARENT_SOURCE : Any,
  PARENT_TARGET : Any,
  >

/** Locates one existing target at a time. */
interface AutoMapExistingEntityLookup<
  INPUT : Any,
  TARGET : Any,
  KEY : Any,
  PARENT_SOURCE : Any,
  PARENT_TARGET : Any,
  > : AutoMapExistingEntityLocator<INPUT, TARGET, KEY, PARENT_SOURCE, PARENT_TARGET> {

  /** Returning `null` means this strategy does not apply to the input. */
  fun getLookupKey(
    input: INPUT,
    context: AutoMapExistingEntityLookupContext<INPUT, TARGET, PARENT_SOURCE, PARENT_TARGET>,
  ): KEY?

  /** Returning `null` means no target was found and resolution continues. */
  fun findExisting(
    input: INPUT,
    context: AutoMapExistingEntityLookupContext<INPUT, TARGET, PARENT_SOURCE, PARENT_TARGET>,
  ): TARGET?

  /** Domain-specific ownership validation; exceptions are propagated unchanged. */
  fun validateExisting(
    target: TARGET,
    input: INPUT,
    context: AutoMapExistingEntityLookupContext<INPUT, TARGET, PARENT_SOURCE, PARENT_TARGET>,
  ) = Unit
}

/** Locates collection targets with one loader invocation for all uncached keys. */
interface AutoMapBatchExistingEntityLookup<
  INPUT : Any,
  TARGET : Any,
  KEY : Any,
  PARENT_SOURCE : Any,
  PARENT_TARGET : Any,
  > : AutoMapExistingEntityLocator<INPUT, TARGET, KEY, PARENT_SOURCE, PARENT_TARGET> {

  /** Returning `null` means this strategy does not apply to the input. */
  fun getLookupKey(
    input: INPUT,
    context: AutoMapExistingEntityLookupContext<INPUT, TARGET, PARENT_SOURCE, PARENT_TARGET>,
  ): KEY?

  fun loadExisting(
    keys: Set<KEY>,
    inputs: List<INPUT>,
    context: AutoMapBatchExistingEntityLookupContext<INPUT, TARGET, PARENT_SOURCE, PARENT_TARGET>,
  ): Map<KEY, TARGET>

  /** Domain-specific ownership validation; exceptions are propagated unchanged. */
  fun validateExisting(
    target: TARGET,
    input: INPUT,
    context: AutoMapExistingEntityLookupContext<INPUT, TARGET, PARENT_SOURCE, PARENT_TARGET>,
  ) = Unit
}

enum class AutoMapExistingEntityLookupOrder {
  ID_FIRST,
  CUSTOM_FIRST,
}

enum class AutoMapExistingEntityConflictPolicy {
  FIRST_MATCH,
  FAIL_ON_CONFLICT,
}
