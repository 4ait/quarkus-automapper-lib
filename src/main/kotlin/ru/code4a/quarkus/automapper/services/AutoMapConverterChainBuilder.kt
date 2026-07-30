package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.utils.cast.castNullableElseError
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.isSubclassOf

internal object AutoMapConverterChainBuilder {

  fun interface AutoMapDynConverter {
    fun convert(
      autoMapper: AutoMapper,
      allowedCreationObjectClasses: Set<KClass<*>>,
      allowedUpdateObjectClasses: Set<KClass<*>>,
      mappingContext: AutoMapMappingFrame,
      input: Any?
    ): Any?
  }

  fun build(
    fromType: KType,
    toType: KType,
    defaultConverters: AutoMapTypeDefaultConverters,
    mapperSpec: KClass<*>? = null,
  ): AutoMapDynConverter {
    val fromKClass =
      fromType
        .classifier
        .castNullableElseError<KClass<*>> {
          "From type $fromType is not a class"
        }

    if (fromType.isMarkedNullable && toType.isMarkedNullable == false) {
      error("Types $fromType and $toType is not compilable")
    }

    val canBeProcessed =
      if (fromType.isMarkedNullable) {
        { input: Any? -> input != null }
      } else {
        { input: Any? -> true }
      }

    return when {
      fromKClass.isSubclassOf(Collection::class) -> {
        val fromGenericArgument =
          fromType.arguments[0].type
            ?: error(
              "Value type $fromType have not generic type. " +
                "But should. Maybe types mismatched"
            )

        val toGenericArgument =
          toType.arguments[0].type
            ?: error(
              "Value type $toType have not generic type. " +
                "But should. Maybe types mismatched"
            )

        val collectionToContainerConverter =
          buildConverterFromCollectionToContainer(toType)

        val itemConverter = build(fromGenericArgument, toGenericArgument, defaultConverters, mapperSpec)

        val itemMapperSpec =
          mapperSpec
            ?: (fromGenericArgument.classifier as? KClass<*>)
              ?.takeIf { it.findAnnotations(AutoMapObjectFromInput::class).isNotEmpty() }

        AutoMapDynConverter { autoMapper: AutoMapper,
                              allowedCreationObjectClasses: Set<KClass<*>>,
                              allowedUpdateObjectClasses: Set<KClass<*>>,
                              mappingContext: AutoMapMappingFrame,
                              input: Any? ->
          if (canBeProcessed(input)) {
            if (input == null) {
              error("Input must be present")
            }

            val inputCollection = input as Collection<Any?>

            if (itemMapperSpec != null) {
              autoMapper.prepareExistingEntityLookups(
                mapperSpec = itemMapperSpec,
                inputs = inputCollection.filterNotNull(),
                parentContext = mappingContext,
              )
            }

            collectionToContainerConverter(
              inputCollection.map {
                itemConverter.convert(
                  autoMapper = autoMapper,
                  allowedCreationObjectClasses = allowedCreationObjectClasses,
                  allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                  mappingContext = mappingContext,
                  input = it
                )
              }
            )
          } else {
            null
          }
        }
      }

      else -> {
        val toKClass = toType.classifier.castNullableElseError<KClass<*>> { "To type $toType is not a class" }

        when {
          toKClass.isSubclassOf(Collection::class) -> {
            val toGenericArgument =
              toType.arguments[0].type
                ?: error(
                  "Value type $toType have not generic type. " +
                    "But should. Maybe types mismatched"
                )

            val collectionToContainerConverter =
              buildConverterFromCollectionToContainer(toType)

            val itemConverter = build(fromType, toGenericArgument, defaultConverters, mapperSpec)

            AutoMapDynConverter { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
                                  mappingContext: AutoMapMappingFrame,
                                  input: Any? ->
              collectionToContainerConverter(
                listOf(
                  itemConverter.convert(
                    autoMapper = autoMapper,
                    allowedCreationObjectClasses = allowedCreationObjectClasses,
                    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                    mappingContext = mappingContext,
                    input = input
                  )
                )
              )
            }
          }

          mapperSpec != null || fromKClass.findAnnotations(AutoMapObjectFromInput::class).isNotEmpty() -> {
            AutoMapDynConverter { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
                                  mappingContext: AutoMapMappingFrame,
                                  input: Any? ->
              if (canBeProcessed(input)) {
                if (input == null) {
                  error("Input must be present")
                }

                autoMapper
                  .internalCreateOrUpdateObjectByInput(
                    mapperSpec ?: input::class,
                    allowedCreationObjectClasses,
                    allowedUpdateObjectClasses,
                    input,
                    parentContext = mappingContext,
                  )
              } else {
                null
              }
            }
          }

          fromType.classifier.castNullableElseError<KClass<*>> { "Type $fromType is not a class" } != toKClass -> {
            val defaultConverter =
              defaultConverters.getDefaultConverter(
                (fromType.classifier.castNullableElseError<KClass<*>> { "Type $fromType is not a class" }).java as Class<Any>,
                toKClass.java as Class<Any>
              )

            AutoMapDynConverter { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
                                  mappingContext: AutoMapMappingFrame,
                                  input: Any? ->
              if (canBeProcessed(input)) {
                defaultConverter.convert(
                  input
                    .unwrapElseError {
                      "input must not be null"
                    }
                )
              } else {
                null
              }
            }
          }

          else -> {
            AutoMapDynConverter { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
                                  mappingContext: AutoMapMappingFrame,
                                  input: Any? ->
              input
            }
          }
        }
      }
    }

  }

  /** Performs all type inspection during STATIC_INIT and leaves only component binding for runtime. */
  fun buildBlueprint(
    fromType: KType,
    toType: KType,
    defaultConverters: AutoMapTypeDefaultConvertersBlueprint,
    componentReferences: AutoMapComponentReferences,
    mapperSpec: KClass<*>? = null,
  ): AutoMapBinding<AutoMapDynConverter> {
    val fromKClass =
      fromType.classifier.castNullableElseError<KClass<*>> {
        "From type $fromType is not a class"
      }

    if (fromType.isMarkedNullable && !toType.isMarkedNullable) {
      error("Types $fromType and $toType is not compilable")
    }

    val canBeProcessed =
      if (fromType.isMarkedNullable) {
        { input: Any? -> input != null }
      } else {
        { _: Any? -> true }
      }

    return when {
      fromKClass.isSubclassOf(Collection::class) -> {
        val fromGenericArgument =
          fromType.arguments[0].type
            ?: error("Value type $fromType have not generic type. But should. Maybe types mismatched")
        val toGenericArgument =
          toType.arguments[0].type
            ?: error("Value type $toType have not generic type. But should. Maybe types mismatched")
        val collectionToContainerConverter = buildConverterFromCollectionToContainer(toType)
        val itemConverterBinding =
          buildBlueprint(
            fromGenericArgument,
            toGenericArgument,
            defaultConverters,
            componentReferences,
            mapperSpec,
          )
        val itemMapperSpec =
          mapperSpec
            ?: (fromGenericArgument.classifier as? KClass<*>)
              ?.takeIf { it.findAnnotations(AutoMapObjectFromInput::class).isNotEmpty() }

        itemConverterBinding.map { itemConverter ->
          AutoMapDynConverter { autoMapper,
                                allowedCreationObjectClasses,
                                allowedUpdateObjectClasses,
                                mappingContext,
                                input ->
            if (canBeProcessed(input)) {
              if (input == null) error("Input must be present")
              val inputCollection = input as Collection<Any?>

              if (itemMapperSpec != null) {
                autoMapper.prepareExistingEntityLookups(
                  mapperSpec = itemMapperSpec,
                  inputs = inputCollection.filterNotNull(),
                  parentContext = mappingContext,
                )
              }

              collectionToContainerConverter(
                inputCollection.map { item ->
                  itemConverter.convert(
                    autoMapper = autoMapper,
                    allowedCreationObjectClasses = allowedCreationObjectClasses,
                    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                    mappingContext = mappingContext,
                    input = item,
                  )
                }
              )
            } else {
              null
            }
          }
        }
      }

      else -> {
        val toKClass =
          toType.classifier.castNullableElseError<KClass<*>> { "To type $toType is not a class" }

        when {
          toKClass.isSubclassOf(Collection::class) -> {
            val toGenericArgument =
              toType.arguments[0].type
                ?: error("Value type $toType have not generic type. But should. Maybe types mismatched")
            val collectionToContainerConverter = buildConverterFromCollectionToContainer(toType)
            val itemConverterBinding =
              buildBlueprint(
                fromType,
                toGenericArgument,
                defaultConverters,
                componentReferences,
                mapperSpec,
              )

            itemConverterBinding.map { itemConverter ->
              AutoMapDynConverter { autoMapper,
                                    allowedCreationObjectClasses,
                                    allowedUpdateObjectClasses,
                                    mappingContext,
                                    input ->
                collectionToContainerConverter(
                  listOf(
                    itemConverter.convert(
                      autoMapper = autoMapper,
                      allowedCreationObjectClasses = allowedCreationObjectClasses,
                      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                      mappingContext = mappingContext,
                      input = input,
                    )
                  )
                )
              }
            }
          }

          mapperSpec != null || fromKClass.findAnnotations(AutoMapObjectFromInput::class).isNotEmpty() -> {
            AutoMapBinding.fixed(
              AutoMapDynConverter { autoMapper,
                                    allowedCreationObjectClasses,
                                    allowedUpdateObjectClasses,
                                    mappingContext,
                                    input ->
                if (canBeProcessed(input)) {
                  if (input == null) error("Input must be present")
                  autoMapper.internalCreateOrUpdateObjectByInput(
                    mapperSpec = mapperSpec ?: input::class,
                    allowedCreationObjectClasses = allowedCreationObjectClasses,
                    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                    input = input,
                    parentContext = mappingContext,
                  )
                } else {
                  null
                }
              }
            )
          }

          fromKClass != toKClass -> {
            @Suppress("UNCHECKED_CAST")
            val defaultConverterReference =
              defaultConverters.getDefaultConverterReference(
                fromKClass.java as Class<Any>,
                toKClass.java as Class<Any>,
                componentReferences,
              )

            AutoMapBinding.fixed(
              AutoMapDynConverter { _, _, _, _, input ->
                if (canBeProcessed(input)) {
                  defaultConverterReference.get().convert(
                    input.unwrapElseError { "input must not be null" }
                  )
                } else {
                  null
                }
              }
            )
          }

          else -> AutoMapBinding.fixed(AutoMapDynConverter { _, _, _, _, input -> input })
        }
      }
    }
  }


  private fun buildConverterFromCollectionToContainer(containerType: KType): (Collection<Any?>) -> Any? {
    return when (containerType.classifier.castNullableElseError<KClass<*>> { "Type $containerType is not a class" }) {
      List::class -> {
        { input: Collection<*> ->
          input.toList()
        }
      }

      Set::class -> {
        { input: Collection<*> ->
          input.toSet()
        }
      }

      else -> error("Is not supported container type $containerType")
    }
  }
}
