package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.interfaces.AutoMapBatchExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLocator
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookup
import ru.code4a.quarkus.automapper.meta.BatchExistingEntityLocatorInfo
import ru.code4a.quarkus.automapper.meta.ExistingEntityLocatorInfo
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability

/** Builds type-erased execution adapters from build-time validated locator metadata. */
internal object AutoMapExistingEntityLocatorIntrospector {

  class Blueprint(
    private val preparedBinding: AutoMapBinding<ExistingEntityLocatorInfo>,
  ) {
    fun bind(componentResolver: AutoMapComponentResolver): ExistingEntityLocatorInfo {
      return binding().bind(componentResolver)
    }

    fun binding(): AutoMapBinding<ExistingEntityLocatorInfo> {
      return preparedBinding
    }
  }

  fun introspect(
    locatorClass: KClass<out AutoMapExistingEntityLocator<*, *, *, *, *>>,
    inputKClass: KClass<*>,
    targetKClass: KClass<*>,
    componentResolver: AutoMapComponentResolver,
    prevalidatedKind: AutoMapExistingEntityLocatorKind? = null,
  ): ExistingEntityLocatorInfo {
    return blueprint(
      locatorClass = locatorClass,
      inputKClass = inputKClass,
      targetKClass = targetKClass,
      prevalidatedKind = prevalidatedKind,
    ).bind(componentResolver)
  }

  fun blueprint(
    locatorClass: KClass<out AutoMapExistingEntityLocator<*, *, *, *, *>>,
    inputKClass: KClass<*>,
    targetKClass: KClass<*>,
    prevalidatedKind: AutoMapExistingEntityLocatorKind? = null,
    componentReferences: AutoMapComponentReferences? = null,
  ): Blueprint {
    val runtimeContract =
      if (prevalidatedKind == null) {
        inspectAndValidateRuntimeContract(locatorClass, inputKClass, targetKClass)
      } else {
        null
      }
    val kind = prevalidatedKind ?: runtimeContract!!.kind
    @Suppress("UNCHECKED_CAST")
    val typedLocatorClass =
      locatorClass as KClass<AutoMapExistingEntityLocator<*, *, *, *, *>>
    val missingMessage = {
      "Existing entity lookup $locatorClass must be an object instance or an @ApplicationScoped CDI bean"
    }
    val infoBinding =
      if (componentReferences == null) {
        componentBinding(
          componentClass = typedLocatorClass,
          missingMessage = missingMessage,
        ).map { instance ->
          createInfo(
            locatorClass,
            kind,
            runtimeContract?.parentSourceType,
            runtimeContract?.parentTargetType,
          ) { instance }
        }
      } else {
        val reference =
          componentReferences.reference(
            componentClass = typedLocatorClass,
            missingMessage = missingMessage,
          )
        AutoMapBinding.fixed(
          createInfo(
            locatorClass,
            kind,
            runtimeContract?.parentSourceType,
            runtimeContract?.parentTargetType,
          ) { reference.get() }
        )
      }

    return Blueprint(infoBinding)
  }

  private fun createInfo(
    locatorClass: KClass<out AutoMapExistingEntityLocator<*, *, *, *, *>>,
    kind: AutoMapExistingEntityLocatorKind,
    parentSourceType: KType?,
    parentTargetType: KType?,
    instance: () -> AutoMapExistingEntityLocator<*, *, *, *, *>,
  ): ExistingEntityLocatorInfo {
    @Suppress("UNCHECKED_CAST")
    return when (kind) {
      AutoMapExistingEntityLocatorKind.SINGLE -> {
        ExistingEntityLocatorInfoAdapter(
          locatorClass = locatorClass,
          parentSourceType = parentSourceType,
          parentTargetType = parentTargetType,
          keyGetter = { input, context ->
            val locator = instance() as AutoMapExistingEntityLookup<Any, Any, Any, Any, Any>
            locator.getLookupKey(input, context.toLookupContext())
          },
          finder = { input, context ->
            val locator = instance() as AutoMapExistingEntityLookup<Any, Any, Any, Any, Any>
            locator.findExisting(input, context.toLookupContext())
          },
          validator = { target, input, context ->
            val locator = instance() as AutoMapExistingEntityLookup<Any, Any, Any, Any, Any>
            locator.validateExisting(target, input, context.toLookupContext())
          },
        )
      }

      AutoMapExistingEntityLocatorKind.BATCH -> {
        BatchExistingEntityLocatorInfo(
          locatorClass = locatorClass,
          parentSourceType = parentSourceType,
          parentTargetType = parentTargetType,
          keyGetter = { input, context ->
            val locator = instance() as AutoMapBatchExistingEntityLookup<Any, Any, Any, Any, Any>
            locator.getLookupKey(input, context.toLookupContext())
          },
          loader = { keys, inputs, context ->
            val locator = instance() as AutoMapBatchExistingEntityLookup<Any, Any, Any, Any, Any>
            locator.loadExisting(keys, inputs, context.toBatchLookupContext(inputs))
          },
          validator = { target, input, context ->
            val locator = instance() as AutoMapBatchExistingEntityLookup<Any, Any, Any, Any, Any>
            locator.validateExisting(target, input, context.toLookupContext())
          },
        )
      }
    }
  }

  private class RuntimeContract(
    val kind: AutoMapExistingEntityLocatorKind,
    val parentSourceType: KType,
    val parentTargetType: KType,
  )

  private fun inspectAndValidateRuntimeContract(
    locatorClass: KClass<out AutoMapExistingEntityLocator<*, *, *, *, *>>,
    inputKClass: KClass<*>,
    targetKClass: KClass<*>,
  ): RuntimeContract {
    val supportedSuperTypes =
      locatorClass.allSupertypes.filter { type ->
        val classifier = type.classifier as? KClass<*>
        classifier == AutoMapExistingEntityLookup::class ||
          classifier == AutoMapBatchExistingEntityLookup::class
      }

    require(supportedSuperTypes.size == 1) {
      "Existing entity lookup $locatorClass must implement exactly one of " +
        "${AutoMapExistingEntityLookup::class} or ${AutoMapBatchExistingEntityLookup::class}"
    }

    val locatorType = supportedSuperTypes.single()
    val declaredInputType = locatorType.requiredArgument(0, "input", locatorClass)
    val declaredTargetType = locatorType.requiredArgument(1, "target", locatorClass)
    val keyType = locatorType.requiredArgument(2, "key", locatorClass)
    val parentSourceType = locatorType.requiredArgument(3, "parent source", locatorClass)
    val parentTargetType = locatorType.requiredArgument(4, "parent target", locatorClass)

    requireExact(locatorClass, "input", declaredInputType, inputKClass.starProjectedType)
    requireExact(locatorClass, "target", declaredTargetType, targetKClass.starProjectedType)
    requireConcrete(locatorClass, "key", keyType)
    requireConcrete(locatorClass, "parent source", parentSourceType)
    requireConcrete(locatorClass, "parent target", parentTargetType)

    return RuntimeContract(
      kind =
        when (locatorType.classifier) {
          AutoMapExistingEntityLookup::class -> AutoMapExistingEntityLocatorKind.SINGLE
          AutoMapBatchExistingEntityLookup::class -> AutoMapExistingEntityLocatorKind.BATCH
          else -> error("Unsupported existing entity lookup $locatorClass")
        },
      parentSourceType = parentSourceType,
      parentTargetType = parentTargetType,
    )
  }

  private fun AutoMapMappingFrame.toLookupContext():
    AutoMapExistingEntityLookupContext<Any, Any, Any, Any> {
    @Suppress("UNCHECKED_CAST")
    return AutoMapExistingEntityLookupContext(
      source = source,
      targetKClass = targetKClass as KClass<Any>,
      parentSource = parent?.source,
      parentTarget = parent?.target,
      operation = operation,
    )
  }

  private fun AutoMapMappingFrame.toBatchLookupContext(
    inputs: List<Any>,
  ): AutoMapBatchExistingEntityLookupContext<Any, Any, Any, Any> {
    @Suppress("UNCHECKED_CAST")
    return AutoMapBatchExistingEntityLookupContext(
      sources = inputs,
      targetKClass = targetKClass as KClass<Any>,
      parentSource = parent?.source,
      parentTarget = parent?.target,
      operation = operation,
    )
  }

  private fun KType.requiredArgument(
    index: Int,
    role: String,
    locatorClass: KClass<*>,
  ): KType {
    return arguments.getOrNull(index)?.type.unwrapElseError {
      "Existing entity lookup $locatorClass must declare a concrete $role generic type"
    }
  }

  private fun requireConcrete(locatorClass: KClass<*>, role: String, type: KType) {
    require(type.classifier is KClass<*> && !type.isMarkedNullable) {
      "Existing entity lookup $locatorClass must declare a concrete non-null $role type, actual $type"
    }
  }

  private fun requireExact(
    locatorClass: KClass<*>,
    role: String,
    declaredType: KType,
    actualType: KType,
  ) {
    requireConcrete(locatorClass, role, declaredType)
    require(declaredType.withNullability(false) == actualType.withNullability(false)) {
      "Existing entity lookup $locatorClass $role type $declaredType is not compatible with $actualType"
    }
  }

  private class ExistingEntityLocatorInfoAdapter(
    locatorClass: KClass<*>,
    parentSourceType: KType?,
    parentTargetType: KType?,
    private val keyGetter: (Any, AutoMapMappingFrame) -> Any?,
    private val finder: (Any, AutoMapMappingFrame) -> Any?,
    private val validator: (Any, Any, AutoMapMappingFrame) -> Unit,
  ) : ExistingEntityLocatorInfo(locatorClass, parentSourceType, parentTargetType) {
    override fun getLookupKey(input: Any, context: AutoMapMappingFrame): Any? {
      return keyGetter(input, context)
    }

    override fun findExisting(input: Any, lookupKey: Any, context: AutoMapMappingFrame): Any? {
      return finder(input, context)
    }

    override fun validateExisting(target: Any, input: Any, context: AutoMapMappingFrame) {
      validator(target, input, context)
    }
  }
}
