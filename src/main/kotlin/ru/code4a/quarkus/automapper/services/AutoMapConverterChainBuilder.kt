package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.utils.cast.castNullableElseError
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.isSubclassOf

object AutoMapConverterChainBuilder {

  fun interface AutoMapDynConverter {
    fun convert(
      autoMapper: AutoMapper,
      allowedCreationObjectClasses: Set<KClass<*>>,
      allowedUpdateObjectClasses: Set<KClass<*>>,
      input: Any?
    ): Any?
  }

  fun build(fromType: KType, toType: KType): AutoMapDynConverter {
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

        val itemConverter = build(fromGenericArgument, toGenericArgument)

        AutoMapDynConverter { autoMapper: AutoMapper,
                              allowedCreationObjectClasses: Set<KClass<*>>,
                              allowedUpdateObjectClasses: Set<KClass<*>>,
                              input: Any? ->
          if (canBeProcessed(input)) {
            if (input == null) {
              error("Input must be present")
            }

            collectionToContainerConverter(
              (input as Collection<Any?>).map {
                itemConverter.convert(
                  autoMapper = autoMapper,
                  allowedCreationObjectClasses = allowedCreationObjectClasses,
                  allowedUpdateObjectClasses = allowedUpdateObjectClasses,
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

            val itemConverter = build(fromType, toGenericArgument)

            AutoMapDynConverter { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
                                  input: Any? ->
              collectionToContainerConverter(
                listOf(
                  itemConverter.convert(
                    autoMapper = autoMapper,
                    allowedCreationObjectClasses = allowedCreationObjectClasses,
                    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                    input = input
                  )
                )
              )
            }
          }

          fromKClass.findAnnotations(AutoMapObjectFromInput::class).isNotEmpty() -> {
            AutoMapDynConverter { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
                                  input: Any? ->
              if (canBeProcessed(input)) {
                if (input == null) {
                  error("Input must be present")
                }

                autoMapper
                  .internalCreateOrUpdateObjectByInput(
                    input::class,
                    allowedCreationObjectClasses,
                    allowedUpdateObjectClasses,
                    input
                  )
              } else {
                null
              }
            }
          }

          fromType.classifier.castNullableElseError<KClass<*>> { "Type $fromType is not a class" } != toKClass -> {
            val defaultConverter =
              AutoMapTypeDefaultConverters.getDefaultConverter(
                (fromType.classifier.castNullableElseError<KClass<*>> { "Type $fromType is not a class" }).java as Class<Any>,
                toKClass.java as Class<Any>
              )

            AutoMapDynConverter { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
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
                                  input: Any? ->
              input
            }
          }
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
