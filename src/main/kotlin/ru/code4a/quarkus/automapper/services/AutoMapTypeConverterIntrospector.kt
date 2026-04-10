package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.isSupertypeOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters

internal object AutoMapTypeConverterIntrospector {

  class IntrospectedConverter(
    val instance: AutoMapTypeConverter<Any?, Any?>,
    val inputType: KType,
    val outputType: KType,
  )

  fun introspect(
    typeConverterClass: KClass<out AutoMapTypeConverter<*, *>>
  ): IntrospectedConverter {
    val convertFunctions =
      typeConverterClass
        .memberFunctions
        .filter { function ->
          function.name == AutoMapTypeConverter<*, *>::convert.name &&
            function.valueParameters.size == 1
        }

    require(convertFunctions.size == 1) {
      "Converter $typeConverterClass must have exactly one convert(input) function, " +
        "but found ${convertFunctions.size}"
    }

    val convertFunction = convertFunctions.single()

    val inputType =
      convertFunction
        .valueParameters
        .firstOrNull()
        ?.type
        .unwrapElseError {
          "Function convert first parameter must be present for $typeConverterClass"
        }

    val outputType = convertFunction.returnType

    @Suppress("UNCHECKED_CAST")
    val instance =
      (
        typeConverterClass.objectInstance
          ?: typeConverterClass.createInstance()
        ) as AutoMapTypeConverter<Any?, Any?>

    return IntrospectedConverter(
      instance = instance,
      inputType = inputType,
      outputType = outputType,
    )
  }

  fun requireCompatibility(
    typeConverterClass: KClass<out AutoMapTypeConverter<*, *>>,
    fromType: KType,
    toType: KType,
    introspectedConverter: IntrospectedConverter,
  ) {
    require(
      introspectedConverter.inputType.isSupertypeOf(fromType) ||
        introspectedConverter.inputType == fromType
    ) {
      "First argument of converter $typeConverterClass " +
        "(${introspectedConverter.inputType}) is not compatible with $fromType"
    }

    require(
      introspectedConverter.outputType.isSubtypeOf(toType) ||
        introspectedConverter.outputType == toType
    ) {
      "Return type of converter $typeConverterClass " +
        "(${introspectedConverter.outputType}) is not compatible with $toType"
    }
  }
}
