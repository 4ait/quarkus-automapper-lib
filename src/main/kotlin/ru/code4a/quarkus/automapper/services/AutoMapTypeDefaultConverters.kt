package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.utils.cast.castElseError
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass

object AutoMapTypeDefaultConverters {

  private val fromToConvertersMap: Map<Pair<Class<Any>, Class<Any>>, AutoMapTypeConverter<Any, Any>>

  init {
    val classLoader = Thread.currentThread().contextClassLoader

    val converterClassNames =
      classLoader
        .getResource("ru/code4a/quarkus/automapper/automaptypeconverters")
        .unwrapElseError { "Cannot find resource /ru/code4a/quarkus/automapper/automaptypeconverters" }
        .readText()
        .split("\n")

    fromToConvertersMap =
      converterClassNames.associate { converterClassName ->
        val converterClass = classLoader.loadClass(converterClassName)

        val converterKClass = converterClass.kotlin

        val typeConverterSupertype =
          converterKClass
            .supertypes
            .find { klass ->
              klass
                .classifier
                .unwrapElseError {
                  "Supertype of $converterClassName must have classifier"
                } as KClass<*> == AutoMapTypeConverter::class
            }
            .unwrapElseError {
              "Auto map class converter $converterClassName must extend AutoMapTypeConverter"
            }

        val fromKClass =
          typeConverterSupertype
            .arguments
            .firstOrNull()
            .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have first argument" }
            .type
            .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have type for first argument" }
            .classifier
            .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have classifier for first argument" }
            .castElseError<KClass<*>> {
              "AutoMapTypeConverter of $converterClassName must have classifier as KClass for first argument"
            }

        val toKClass =
          typeConverterSupertype
            .arguments
            .getOrNull(1)
            .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have second argument" }
            .type
            .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have type for second argument" }
            .classifier
            .unwrapElseError { "AutoMapTypeConverter of $converterClassName must have classifier for second argument" }
            .castElseError<KClass<*>> {
              "AutoMapTypeConverter of $converterClassName must have classifier as KClass for second argument"
            }

        val converterObj =
          converterClass
            .constructors
            .first()
            .newInstance()
            .castElseError<AutoMapTypeConverter<Any, Any>> {
              "Cannot cast constructor of $converterClass to AutoMapTypeConverter<Any, Any>"
            }

        (Pair(fromKClass.java as Class<Any>, toKClass.java as Class<Any>) to converterObj)
      }
  }

  fun getDefaultConverter(fromClass: Class<Any>, toClass: Class<Any>): AutoMapTypeConverter<Any, Any> {
    return fromToConvertersMap[Pair(fromClass, toClass)]
      .unwrapElseError {
        "Cannot find converter for $fromClass to $toClass"
      }
  }
}
