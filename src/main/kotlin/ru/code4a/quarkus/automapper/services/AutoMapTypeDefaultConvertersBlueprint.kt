package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.utils.cast.castElseError
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass

/** Default converter contracts resolved during STATIC_INIT, before CDI references are available. */
internal class AutoMapTypeDefaultConvertersBlueprint(
  private val configuredConverterClassNames: List<String>? = null,
) {
  private val converterClassesByTypes:
    Map<Pair<Class<Any>, Class<Any>>, KClass<AutoMapTypeConverter<Any, Any>>> = buildClasses()

  fun getDefaultConverterReference(
    fromClass: Class<Any>,
    toClass: Class<Any>,
    componentReferences: AutoMapComponentReferences,
  ): AutoMapComponentReference<AutoMapTypeConverter<Any, Any>> {
    val converterClass =
      converterClassesByTypes[Pair(fromClass, toClass)]
        .unwrapElseError { "Cannot find converter for $fromClass to $toClass" }
    return componentReferences.reference(
      componentClass = converterClass,
      allowNoArgConstruction = true,
      missingMessage = { "Cannot resolve $converterClass as AutoMapTypeConverter<Any, Any>" },
    )
  }

  private fun buildClasses():
    Map<Pair<Class<Any>, Class<Any>>, KClass<AutoMapTypeConverter<Any, Any>>> {
    val classLoader = Thread.currentThread().contextClassLoader
    val converterClassNames =
      configuredConverterClassNames
        ?: classLoader
          .getResource("ru/code4a/quarkus/automapper/automaptypeconverters")
          ?.readText()
          .orEmpty()
          .lineSequence()
          .filter(String::isNotBlank)
          .toList()

    return converterClassNames.associate { converterClassName ->
      val converterClass = classLoader.loadClass(converterClassName)
      val converterKClass = converterClass.kotlin
      val typeConverterSupertype =
        converterKClass
          .supertypes
          .find { type ->
            type.classifier.unwrapElseError {
              "Supertype of $converterClassName must have classifier"
            } as KClass<*> == AutoMapTypeConverter::class
          }
          .unwrapElseError {
            "Auto map class converter $converterClassName must extend AutoMapTypeConverter"
          }
      val fromKClass = typeArgumentClass(typeConverterSupertype, 0, "first", converterClassName)
      val toKClass = typeArgumentClass(typeConverterSupertype, 1, "second", converterClassName)

      @Suppress("UNCHECKED_CAST")
      val typedConverterClass = converterKClass as KClass<AutoMapTypeConverter<Any, Any>>
      Pair(fromKClass.java as Class<Any>, toKClass.java as Class<Any>) to typedConverterClass
    }
  }

  private fun typeArgumentClass(
    converterType: kotlin.reflect.KType,
    index: Int,
    positionName: String,
    converterClassName: String,
  ): KClass<*> {
    return converterType.arguments.getOrNull(index)
      .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have $positionName argument" }
      .type
      .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have type for $positionName argument" }
      .classifier
      .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have classifier for $positionName argument" }
      .castElseError {
        "AutoMapTypeConverter of $converterClassName must have classifier as KClass for $positionName argument"
      }
  }
}
