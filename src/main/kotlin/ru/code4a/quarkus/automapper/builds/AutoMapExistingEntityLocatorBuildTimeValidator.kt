package ru.code4a.quarkus.automapper.builds

import io.quarkus.deployment.util.JandexUtil
import org.jboss.jandex.AnnotationInstance
import org.jboss.jandex.ClassInfo
import org.jboss.jandex.DotName
import org.jboss.jandex.IndexView
import org.jboss.jandex.Type
import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.interfaces.AutoMapBatchExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.services.AutoMapExistingEntityLocatorKind

/** Validates locator generic contracts entirely from the augmentation index. */
internal object AutoMapExistingEntityLocatorBuildTimeValidator {
  private val autoMapObjectName = DotName.createSimple(AutoMapObjectFromInput::class.java)
  private val autoMapFieldName = DotName.createSimple(AutoMapField::class.java)
  private val mapperSpecName = DotName.createSimple(AutoMapperSpec::class.java)
  private val mapperSpecToName = DotName.createSimple(AutoMapperSpecTo::class.java)
  private val singleLookupName = DotName.createSimple(AutoMapExistingEntityLookup::class.java)
  private val batchLookupName = DotName.createSimple(AutoMapBatchExistingEntityLookup::class.java)
  private val javaObjectName = DotName.createSimple(Any::class.java)
  private val collectionNames =
    setOf(
      DotName.createSimple(Collection::class.java),
      DotName.createSimple(List::class.java),
      DotName.createSimple(Set::class.java),
    )

  private class MapperContract(
    val mapperClassName: DotName,
    val inputType: Type,
    val targetType: Type,
    val locatorContracts: List<LocatorContract>,
  )

  private class LocatorContract(
    val locatorClassName: DotName,
    val kind: AutoMapExistingEntityLocatorKind,
    val parentSourceType: Type,
    val parentTargetType: Type,
  )

  fun validate(index: IndexView): Map<DotName, AutoMapExistingEntityLocatorKind> {
    val mapperAnnotations = index.getAnnotations(autoMapObjectName)
    val mapperContracts =
      mapperAnnotations.associate { annotation ->
        val mapperClassName = annotation.target().asClass().name()
        val mappingDirection = resolveMappingDirection(mapperClassName, index)
        val locatorContracts =
          annotation.existingEntityLookupClasses().map { locatorClassName ->
            validateLocator(
              locatorClassName = locatorClassName,
              inputType = mappingDirection.first,
              targetType = mappingDirection.second,
              index = index,
            )
          }

        mapperClassName to
          MapperContract(
            mapperClassName = mapperClassName,
            inputType = mappingDirection.first,
            targetType = mappingDirection.second,
            locatorContracts = locatorContracts,
          )
      }

    validateParentTypes(mapperContracts, index)

    return mapperContracts.values
      .flatMap(MapperContract::locatorContracts)
      .associate { contract -> contract.locatorClassName to contract.kind }
  }

  private fun resolveMappingDirection(mapperClassName: DotName, index: IndexView): Pair<Type, Type> {
    val specToArguments = JandexUtil.resolveTypeParameters(mapperClassName, mapperSpecToName, index)
    if (specToArguments.isNotEmpty()) {
      require(specToArguments.size == 1 && specToArguments.single().isConcrete()) {
        "Mapper $mapperClassName must declare a concrete target type for $mapperSpecToName"
      }
      return Type.create(mapperClassName, Type.Kind.CLASS) to specToArguments.single()
    }

    val specArguments = JandexUtil.resolveTypeParameters(mapperClassName, mapperSpecName, index)
    require(specArguments.size == 2 && specArguments.all { type -> type.isConcrete() }) {
      "Mapper $mapperClassName must declare concrete input and target types for $mapperSpecName"
    }
    return specArguments[0] to specArguments[1]
  }

  private fun validateLocator(
    locatorClassName: DotName,
    inputType: Type,
    targetType: Type,
    index: IndexView,
  ): LocatorContract {
    val supportedContracts =
      listOf(
        AutoMapExistingEntityLocatorKind.SINGLE to singleLookupName,
        AutoMapExistingEntityLocatorKind.BATCH to batchLookupName,
      ).mapNotNull { (kind, interfaceName) ->
        JandexUtil
          .resolveTypeParameters(locatorClassName, interfaceName, index)
          .takeIf(List<Type>::isNotEmpty)
          ?.let { arguments -> Triple(kind, interfaceName, arguments) }
      }

    require(supportedContracts.size == 1) {
      "Existing entity lookup $locatorClassName must implement exactly one of " +
        "$singleLookupName or $batchLookupName"
    }

    val (kind, _, arguments) = supportedContracts.single()
    require(arguments.size == 5) {
      "Existing entity lookup $locatorClassName must declare 5 concrete generic types"
    }

    val roles = listOf("input", "target", "key", "parent source", "parent target")
    arguments.forEachIndexed { index, type ->
      require(type.isConcrete()) {
        "Existing entity lookup $locatorClassName must declare a concrete non-null ${roles[index]} type, actual $type"
      }
    }

    require(arguments[0].name() == inputType.name()) {
      "Existing entity lookup $locatorClassName input type ${arguments[0]} is not compatible with $inputType"
    }
    require(arguments[1].name() == targetType.name()) {
      "Existing entity lookup $locatorClassName target type ${arguments[1]} is not compatible with $targetType"
    }

    return LocatorContract(
      locatorClassName = locatorClassName,
      kind = kind,
      parentSourceType = arguments[3],
      parentTargetType = arguments[4],
    )
  }

  private fun validateParentTypes(
    mapperContracts: Map<DotName, MapperContract>,
    index: IndexView,
  ) {
    mapperContracts.values.forEach { parentContract ->
      val parentClass = requireNotNull(index.getClassByName(parentContract.mapperClassName))
      parentClass.nestedMapperClassNames(mapperContracts.keys, index).forEach { childMapperClassName ->
        val childContract = mapperContracts.getValue(childMapperClassName)

        childContract.locatorContracts.forEach { locator ->
          require(locator.parentSourceType.name() == parentContract.inputType.name()) {
            "Existing entity lookup ${locator.locatorClassName} parent source type " +
              "${locator.parentSourceType} is not compatible with ${parentContract.inputType}"
          }
          require(locator.parentTargetType.name() == parentContract.targetType.name()) {
            "Existing entity lookup ${locator.locatorClassName} parent target type " +
              "${locator.parentTargetType} is not compatible with ${parentContract.targetType}"
          }
        }
      }
    }
  }

  private fun ClassInfo.nestedMapperClassNames(
    mapperClassNames: Set<DotName>,
    index: IndexView,
  ): Set<DotName> {
    return allMethods(index)
      .asSequence()
      .filter { method -> method.parametersCount() == 0 }
      .mapNotNull { method ->
        val explicitMapper =
          method
            .annotation(autoMapFieldName)
            ?.value("mapper")
            ?.asClass()
            ?.name()
            ?.takeUnless { className -> className == javaObjectName }

        explicitMapper
          ?: method.returnType().nestedValueType().name().takeIf(mapperClassNames::contains)
      }
      .toSet()
  }

  private fun ClassInfo.allMethods(index: IndexView): List<org.jboss.jandex.MethodInfo> {
    val methods = mutableListOf<org.jboss.jandex.MethodInfo>()
    val visited = mutableSetOf<DotName>()

    fun collect(classInfo: ClassInfo?) {
      if (classInfo == null || !visited.add(classInfo.name())) return

      methods += classInfo.methods()
      classInfo.interfaceTypes().forEach { interfaceType ->
        collect(index.getClassByName(interfaceType.name()))
      }
      classInfo
        .superClassType()
        ?.name()
        ?.takeUnless { className -> className == javaObjectName }
        ?.let(index::getClassByName)
        ?.let(::collect)
    }

    collect(this)
    return methods
  }

  private fun Type.nestedValueType(): Type {
    return if (kind() == Type.Kind.PARAMETERIZED_TYPE && name() in collectionNames) {
      asParameterizedType().arguments().firstOrNull() ?: this
    } else {
      this
    }
  }

  private fun Type.isConcrete(): Boolean {
    return when (kind()) {
      Type.Kind.CLASS,
      Type.Kind.PRIMITIVE,
      -> true

      Type.Kind.ARRAY -> asArrayType().constituent().isConcrete()
      Type.Kind.PARAMETERIZED_TYPE -> asParameterizedType().arguments().all { type -> type.isConcrete() }
      else -> false
    }
  }

  private fun AnnotationInstance.existingEntityLookupClasses(): List<DotName> {
    return value("existingEntityLookupClasses")
      ?.asClassArray()
      ?.map { type -> type.name() }
      .orEmpty()
  }
}
