package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSupertypeOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.valueParameters
import kotlin.reflect.full.withNullability

internal object AutoMapObjectGetterIntrospector {

  class IntrospectedGetter(
    val instance: Any,
    val getterFunction: kotlin.reflect.KFunction<*>,
  )

  fun introspect(
    objectGetterClass: KClass<*>
  ): IntrospectedGetter {
    val getterFunctions =
      objectGetterClass
        .memberFunctions
        .filter { function ->
          function.name == "get" &&
            function.visibility == KVisibility.PUBLIC
        }

    require(getterFunctions.size == 1) {
      "Entity getter class $objectGetterClass must have exactly one get(entityClass, id) function, " +
        "but found ${getterFunctions.size}"
    }

    val getterFunction = getterFunctions.single()

    require(getterFunction.valueParameters.size == 2) {
      "Getter function $getterFunction of entity getter class $objectGetterClass must have 2 parameters"
    }

    val instance =
      objectGetterClass.objectInstance
        .unwrapElseError {
          "Object Instance must be present for class $objectGetterClass"
        }

    return IntrospectedGetter(
      instance = instance,
      getterFunction = getterFunction,
    )
  }

  fun requireCompatibility(
    objectGetterClass: KClass<*>,
    objectKClass: KClass<*>,
    idType: KType,
    introspectedGetter: IntrospectedGetter,
  ) {
    val entityClassType =
      KClass::class.createType(
        arguments = listOf(KTypeProjection.invariant(objectKClass.starProjectedType))
      )

    val entityClassParameterType =
      introspectedGetter
        .getterFunction
        .valueParameters
        .firstOrNull()
        ?.type
        .unwrapElseError {
          "Function get first parameter must be present for $objectGetterClass"
        }

    require(
      entityClassParameterType.isSupertypeOf(entityClassType) ||
        entityClassParameterType == entityClassType
    ) {
      "First argument of getter $objectGetterClass " +
        "(${entityClassParameterType}) is not compatible with $entityClassType"
    }

    val expectedIdType =
      idType.withNullability(false)

    val idParameterType =
      introspectedGetter
        .getterFunction
        .valueParameters
        .getOrNull(1)
        ?.type
        .unwrapElseError {
          "Function get second parameter must be present for $objectGetterClass"
        }

    require(
      idParameterType.isSupertypeOf(expectedIdType) ||
        idParameterType == expectedIdType
    ) {
      "Second argument of getter $objectGetterClass " +
        "(${idParameterType}) is not compatible with $expectedIdType"
    }

    val expectedReturnType = objectKClass.starProjectedType
    val returnType = introspectedGetter.getterFunction.returnType

    require(
      returnType.isSupertypeOf(expectedReturnType) ||
        returnType == expectedReturnType
    ) {
      "Return type of getter $objectGetterClass " +
        "(${returnType}) is not compatible with $expectedReturnType"
    }
  }
}
