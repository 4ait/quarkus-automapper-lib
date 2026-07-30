package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.exceptions.FieldCannotBeNullInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.FieldIsNotSupportedForCreateInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.MissingRequiredFieldInputAutomapperException
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityConflictPolicy
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookupOrder
import ru.code4a.quarkus.automapper.meta.ExistingEntityLocatorInfo
import ru.code4a.quarkus.automapper.meta.InputClassInfo
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import ru.code4a.quarkus.automapper.utils.reflection.getReadableName
import kotlin.reflect.KClass
import kotlin.reflect.KParameter

class AutoMapper internal constructor(
  internal val inputClassInfoProvidersByMapperSpecClass:
    Map<Class<*>, AutoMapInputClassInfoProvider>,
) {

  private fun getInputClassInfo(mapperSpec: KClass<*>): InputClassInfo {
    val mapperClass = mapperSpec.java
    return (
      inputClassInfoProvidersByMapperSpecClass[mapperClass]
        ?: error("Cannot find input info for mapper spec $mapperClass")
      ).get() as InputClassInfo
  }

  fun <TO : Any, FROM : Any, T : AutoMapperSpec<FROM, TO>> createOrUpdateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: FROM
  ): TO {
    return internalCreateOrUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input
    ) as TO
  }

  fun <TO : Any, T : AutoMapperSpecTo<TO>> createOrUpdateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: T
  ): TO {
    return internalCreateOrUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input
    ) as TO
  }

  fun <TO : Any, FROM : Any, T : AutoMapperSpec<FROM, TO>> updateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: FROM,
    obj: TO
  ) {
    internalUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input,
      obj = obj
    )
  }

  fun <TO : Any, T : AutoMapperSpecTo<TO>> updateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: T,
    obj: TO
  ) {
    internalUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input,
      obj = obj
    )
  }

  /**
   * Creates or updates an object based on the input.
   *
   * @param allowedCreationObjectClasses The set of allowed creation entity classes.
   * @param allowedUpdateObjectClasses The set of allowed update entity classes.
   * @param input The input object.
   * @return The created or updated entity or object.
   */
  internal fun internalCreateOrUpdateObjectByInput(
    mapperSpec: KClass<*>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: Any,
    parentContext: AutoMapMappingFrame? = null,
  ): Any {
    val inputClassInfo = getInputClassInfo(mapperSpec)

    val mappingContext =
      AutoMapMappingFrame(
        source = input,
        targetKClass = inputClassInfo.objectKClass,
        parent = parentContext,
        operation = parentContext?.operation ?: AutoMapOperationContext(),
      )

    val entity = resolveExistingEntity(inputClassInfo, input, mappingContext)

    if (entity != null) {
      mappingContext.target = entity

      if (inputClassInfo.autoMapObjectFromInputAnnotation.allowUpdate) {
        updateObjectWithContext(
          mapperSpec = mapperSpec,
          allowedCreationObjectClasses = allowedCreationObjectClasses,
          allowedUpdateObjectClasses = allowedUpdateObjectClasses,
          input = input,
          obj = entity,
          mappingContext = mappingContext,
        )
      }

      return entity
    }

    val id = inputClassInfo.idGetterField?.function?.call(input)
    if (id != null) {
      error(
        "Object ${inputClassInfo.objectKClass} with ID $id not found when attempting to update. " +
          "Please verify the ID exists and you have permission to access it."
      )
    }

    val objectKClass = inputClassInfo.objectKClass
    if (objectKClass !in allowedCreationObjectClasses) {
      error("Create class $objectKClass is not allowed")
    }

    val created =
      constructObjectFromInput(
        allowedCreationObjectClasses = allowedCreationObjectClasses,
        allowedUpdateObjectClasses = allowedUpdateObjectClasses,
        inputClassInfo = inputClassInfo,
        input = input,
        mappingContext = mappingContext,
      )

    mappingContext.target = created
    return created
  }

  private object IdLookupStrategy

  private data class LocatedEntity(
    val strategy: String,
    val entity: Any,
  )

  private fun resolveExistingEntity(
    inputClassInfo: InputClassInfo,
    input: Any,
    mappingContext: AutoMapMappingFrame,
  ): Any? {
    val annotation = inputClassInfo.autoMapObjectFromInputAnnotation
    val customLocators = inputClassInfo.existingEntityLocators
    val matches = mutableListOf<LocatedEntity>()

    fun accept(match: LocatedEntity?): Any? {
      if (match != null) {
        matches += match

        if (annotation.existingEntityConflictPolicy == AutoMapExistingEntityConflictPolicy.FIRST_MATCH) {
          return match.entity
        }
      }
      return null
    }

    fun resolveCustomLocators(): Any? {
      for (locator in customLocators) {
        val resolved = accept(resolveByCustomLocator(locator, input, mappingContext))
        if (resolved != null) return resolved
      }
      return null
    }

    when (annotation.existingEntityLookupOrder) {
      AutoMapExistingEntityLookupOrder.ID_FIRST -> {
        accept(resolveById(inputClassInfo, input, mappingContext))?.let { return it }
        resolveCustomLocators()?.let { return it }
      }

      AutoMapExistingEntityLookupOrder.CUSTOM_FIRST -> {
        resolveCustomLocators()?.let { return it }
        accept(resolveById(inputClassInfo, input, mappingContext))?.let { return it }
      }
    }

    if (matches.size > 1) {
      val first = matches.first()
      val conflict = matches.drop(1).firstOrNull { it.entity !== first.entity }
      if (conflict != null) {
        error(
          "Existing entity lookup conflict for ${inputClassInfo.objectKClass}: " +
            "${first.strategy} and ${conflict.strategy} returned different target instances"
        )
      }
    }

    return matches.firstOrNull()?.entity
  }

  private fun resolveById(
    inputClassInfo: InputClassInfo,
    input: Any,
    mappingContext: AutoMapMappingFrame,
  ): LocatedEntity? {
    val id = inputClassInfo.idGetterField?.function?.call(input) ?: return null
    val cacheKey =
      AutoMapLookupCacheKey(
        strategyClass = IdLookupStrategy::class,
        targetKClass = inputClassInfo.objectKClass,
        parentTarget = AutoMapIdentityKey(null),
        lookupKey = id,
      )
    val cache = mappingContext.operation.lookupCache
    val entity =
      if (cache.containsKey(cacheKey)) {
        cache[cacheKey]
      } else {
        val found =
          inputClassInfo
            .objectByIdGetter
            .unwrapElseError { "Input ${input::class} must have object by id getter" }
            .getObject(id)
        cache[cacheKey] = found
        found
      }

    return entity?.let { LocatedEntity("standard ID lookup", it) }
  }

  private fun resolveByCustomLocator(
    locator: ExistingEntityLocatorInfo,
    input: Any,
    mappingContext: AutoMapMappingFrame,
  ): LocatedEntity? {
    val strategyName = locator.locatorClass.qualifiedName ?: locator.locatorClass.toString()
    val lookupKey = getCustomLookupKey(locator, input, mappingContext) ?: return null
    val cacheKey = customCacheKey(locator, lookupKey, mappingContext)
    val cache = mappingContext.operation.lookupCache

    val entity =
      if (cache.containsKey(cacheKey)) {
        cache[cacheKey]
      } else {
        val found = locator.findExisting(input, lookupKey, mappingContext)
        cache[cacheKey] = found
        found
      }

    if (entity != null) {
      locator.validateExisting(entity, input, mappingContext)
    }

    return entity?.let { LocatedEntity(strategyName, it) }
  }

  private fun getCustomLookupKey(
    locator: ExistingEntityLocatorInfo,
    input: Any,
    mappingContext: AutoMapMappingFrame,
  ): Any? {
    val sourceKey =
      AutoMapSourceLookupKey(
        strategyClass = locator.locatorClass,
        parentTarget = AutoMapIdentityKey(mappingContext.parent?.target),
        source = AutoMapIdentityKey(input),
      )
    val keyCache = mappingContext.operation.sourceLookupKeys
    if (keyCache.containsKey(sourceKey)) {
      return keyCache[sourceKey]
    }

    val key = locator.getLookupKey(input, mappingContext)
    val stableKey = if (key === input) AutoMapIdentityKey(input) else key
    keyCache[sourceKey] = stableKey
    return stableKey
  }

  private fun customCacheKey(
    locator: ExistingEntityLocatorInfo,
    lookupKey: Any,
    mappingContext: AutoMapMappingFrame,
  ): AutoMapLookupCacheKey {
    return AutoMapLookupCacheKey(
      strategyClass = locator.locatorClass,
      targetKClass = mappingContext.targetKClass,
      parentTarget = AutoMapIdentityKey(mappingContext.parent?.target),
      lookupKey = lookupKey,
    )
  }

  private fun requireLoadedKeys(
    loaded: Map<Any, Any>,
    requestedKeys: Set<Any>,
    locator: ExistingEntityLocatorInfo,
  ) {
    val unexpectedKeys = loaded.keys - requestedKeys
    require(unexpectedKeys.isEmpty()) {
      "Batch existing entity lookup ${locator.locatorClass} returned unexpected keys $unexpectedKeys"
    }
  }

  internal fun prepareExistingEntityLookups(
    mapperSpec: KClass<*>,
    inputs: Collection<Any>,
    parentContext: AutoMapMappingFrame,
  ) {
    if (inputs.isEmpty()) return

    val inputClassInfo = getInputClassInfo(mapperSpec)
    val annotation = inputClassInfo.autoMapObjectFromInputAnnotation
    val batchLocators = inputClassInfo.batchExistingEntityLocators

    for (locator in batchLocators) {
      val eligibleInputs =
        if (
          annotation.existingEntityLookupOrder == AutoMapExistingEntityLookupOrder.ID_FIRST &&
          annotation.existingEntityConflictPolicy == AutoMapExistingEntityConflictPolicy.FIRST_MATCH
        ) {
          inputs.filter { inputClassInfo.idGetterField?.function?.call(it) == null }
        } else {
          inputs.toList()
        }

      val inputsWithContexts =
        eligibleInputs.map { input ->
          input to
            AutoMapMappingFrame(
              source = input,
              targetKClass = inputClassInfo.objectKClass,
              parent = parentContext,
              operation = parentContext.operation,
            )
        }
      val keysByInput =
        inputsWithContexts.mapNotNull { (input, context) ->
          getCustomLookupKey(locator, input, context)?.let { input to it }
        }
      val firstContext = inputsWithContexts.firstOrNull()?.second ?: continue
      val uncachedKeys =
        keysByInput
          .map { it.second }
          .filterTo(linkedSetOf()) { key ->
            !firstContext.operation.lookupCache.containsKey(customCacheKey(locator, key, firstContext))
          }

      if (uncachedKeys.isEmpty()) continue

      val loaderInputs = keysByInput.filter { it.second in uncachedKeys }.map { it.first }
      val loaded =
        locator.loadExisting(
          keys = uncachedKeys,
          inputs = loaderInputs,
          context = firstContext,
        )
      requireLoadedKeys(loaded, uncachedKeys, locator)

      for (key in uncachedKeys) {
        val found = loaded[key]
        firstContext.operation.lookupCache[customCacheKey(locator, key, firstContext)] = found
      }
    }
  }

  /**
   * Update the entity based on the input.
   *
   * @param allowedCreationObjectClasses The set of allowed creation entity classes.
   * @param allowedUpdateObjectClasses The set of allowed update entity classes.
   * @param input The input object.
   * @param obj The entity object to update.
   * @throws RuntimeException If access to update the entity is denied.
   * @throws FieldCannotBeNullInputAutomapperException If a field cannot be null.
   */
  internal fun internalUpdateObjectByInput(
    mapperSpec: KClass<*>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: Any,
    obj: Any,
    parentContext: AutoMapMappingFrame? = null,
  ) {
    val inputClassInfo = getInputClassInfo(mapperSpec)
    val mappingContext =
      AutoMapMappingFrame(
        source = input,
        targetKClass = inputClassInfo.objectKClass,
        parent = parentContext,
        operation = parentContext?.operation ?: AutoMapOperationContext(),
      )
    mappingContext.target = obj

    updateObjectWithContext(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input,
      obj = obj,
      mappingContext = mappingContext,
    )
  }

  private fun updateObjectWithContext(
    mapperSpec: KClass<*>,
    allowedCreationObjectClasses: Set<KClass<*>>,
    allowedUpdateObjectClasses: Set<KClass<*>>,
    input: Any,
    obj: Any,
    mappingContext: AutoMapMappingFrame,
  ) {
    if (obj::class !in allowedUpdateObjectClasses) {
      error("Access to update ${obj::class} is denied")
    }

    val inputClassInfo = getInputClassInfo(mapperSpec)

    inputClassInfo
      .objectByInputUpdater
      .unwrapElseError {
        "Object ${obj::class} cannot be updated by configuration"
      }
      .updateObj(
        autoMapper = this,
        allowedUpdateObjectClasses = allowedUpdateObjectClasses,
        allowedCreationObjectClasses = allowedCreationObjectClasses,
        mappingContext = mappingContext,
        obj = obj,
        input = input
      )
  }

  private fun constructObjectFromInput(
    allowedCreationObjectClasses: Set<KClass<*>>,
    allowedUpdateObjectClasses: Set<KClass<*>>,
    inputClassInfo: InputClassInfo,
    input: Any,
    mappingContext: AutoMapMappingFrame,
  ): Any {
    val inputClassName = input::class.getReadableName()

    val inputCreateInfo =
      inputClassInfo
        .inputCreateInfo
        .unwrapElseError {
          "Object ${inputClassInfo.objectKClass} cannot be created by the configuration"
        }

    val notUsedInputPropertiesByBuildFieldNameSet = inputCreateInfo.createFieldsByName.toMutableMap()

    val methodArgs = mutableMapOf<KParameter, Any?>()

    for (objectCreateParameter in inputCreateInfo.constructMethod.parameters) {
      if (objectCreateParameter.kind == KParameter.Kind.INSTANCE) {
        methodArgs[objectCreateParameter] = inputCreateInfo.constructorObject
        continue
      }

      val createFieldInfo =
        inputCreateInfo
          .createFieldsByName[objectCreateParameter.name]

      val inputFieldGetter =
        createFieldInfo?.inputFieldGetter

      if (inputFieldGetter == null) {
        if (!objectCreateParameter.isOptional) {
          error(
            "Cannot find input property \"${objectCreateParameter}\" inside input class \"${input::class}\" " +
              "for build method \"${inputCreateInfo.constructMethod.name}\" of ${inputClassInfo.objectKClass}"
          )
        }

        continue
      }

      notUsedInputPropertiesByBuildFieldNameSet
        .remove(
          objectCreateParameter.name
        )

      if (true) { // TODO: isSetterCalled
        val value = inputFieldGetter.function.call(input)

        if (value == null && !objectCreateParameter.type.isMarkedNullable) {
          throw FieldCannotBeNullInputAutomapperException(
            fieldName = inputFieldGetter.name,
            className = inputClassName
          )
        }

        methodArgs[objectCreateParameter] =
          createFieldInfo.converter.convert(
            autoMapper = this,
            allowedUpdateObjectClasses = allowedUpdateObjectClasses,
            allowedCreationObjectClasses = allowedCreationObjectClasses,
            mappingContext = mappingContext,
            input = value
          )
      } else if (!objectCreateParameter.isOptional) {
        throw MissingRequiredFieldInputAutomapperException(
          fieldName = inputFieldGetter.name,
          className = inputClassName
        )
      }
    }

    notUsedInputPropertiesByBuildFieldNameSet.forEach { (t, inputCreateFieldInfo) ->
      if (true) { // TODO: isSetterCalled
        throw FieldIsNotSupportedForCreateInputAutomapperException(
          fieldName = inputCreateFieldInfo.inputFieldGetter.name,
          className = inputClassName
        )
      }
    }

    val entity =
      inputCreateInfo.constructMethod.callBy(methodArgs)
        ?: error("Object should be present after creation")

    return entity
  }
}

inline fun <TO : Any, reified T : AutoMapperSpecTo<TO>> AutoMapper.createOrUpdateObjectByInput(
  allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
  allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
  input: T
): TO {
  return createOrUpdateObjectByInput(
    mapperSpec = T::class,
    allowedCreationObjectClasses = allowedCreationObjectClasses,
    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
    input = input,
  ) as TO
}

inline fun <TO : Any, reified T : AutoMapperSpecTo<TO>> AutoMapper.updateObjectByInput(
  allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
  allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
  input: T,
  obj: TO
) {
  updateObjectByInput(
    mapperSpec = T::class,
    allowedCreationObjectClasses = allowedCreationObjectClasses,
    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
    input = input,
    obj = obj
  )
}
