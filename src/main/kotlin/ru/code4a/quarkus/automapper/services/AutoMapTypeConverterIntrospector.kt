package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.isSupertypeOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters

internal object AutoMapTypeConverterIntrospector {

  class IntrospectedConverterContract(
    val inputType: KType,
    val outputType: KType,
  )

  class IntrospectedConverter(
    val instance: AutoMapTypeConverter<Any?, Any?>,
    val inputType: KType,
    val outputType: KType,
  )

  fun introspect(
    typeConverterClass: KClass<out AutoMapTypeConverter<*, *>>,
    componentResolver: AutoMapComponentResolver,
  ): IntrospectedConverter {
    val contract = introspectContract(typeConverterClass)

    @Suppress("UNCHECKED_CAST")
    val instance =
      componentResolver.resolveOrCreate(typeConverterClass) as AutoMapTypeConverter<Any?, Any?>

    return IntrospectedConverter(
      instance = instance,
      inputType = contract.inputType,
      outputType = contract.outputType,
    )
  }

  fun introspectContract(
    typeConverterClass: KClass<out AutoMapTypeConverter<*, *>>,
  ): IntrospectedConverterContract {
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

    return IntrospectedConverterContract(
      inputType = inputType,
      outputType = outputType,
    )
  }

  fun requireCompatibility(
    typeConverterClass: KClass<out AutoMapTypeConverter<*, *>>,
    fromType: KType,
    toType: KType,
    contract: IntrospectedConverterContract,
  ) {
    requireCompatibility(
      typeConverterClass = typeConverterClass,
      fromType = fromType,
      toType = toType,
      inputType = contract.inputType,
      outputType = contract.outputType,
    )
  }

  fun requireCompatibility(
    typeConverterClass: KClass<out AutoMapTypeConverter<*, *>>,
    fromType: KType,
    toType: KType,
    introspectedConverter: IntrospectedConverter,
  ) {
    requireCompatibility(
      typeConverterClass = typeConverterClass,
      fromType = fromType,
      toType = toType,
      inputType = introspectedConverter.inputType,
      outputType = introspectedConverter.outputType,
    )
  }

  private fun requireCompatibility(
    typeConverterClass: KClass<out AutoMapTypeConverter<*, *>>,
    fromType: KType,
    toType: KType,
    inputType: KType,
    outputType: KType,
  ) {
    require(inputType.isSupertypeOf(fromType) || inputType == fromType) {
      "First argument of converter $typeConverterClass " +
        "($inputType) is not compatible with $fromType"
    }

    require(outputType.isSubtypeOf(toType) || outputType == toType) {
      "Return type of converter $typeConverterClass " +
        "($outputType) is not compatible with $toType"
    }
  }
}
