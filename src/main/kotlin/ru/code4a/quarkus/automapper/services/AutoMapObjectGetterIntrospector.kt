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

  class IntrospectedGetterContract(
    val getterFunction: kotlin.reflect.KFunction<*>,
  )

  class IntrospectedGetter(
    val instance: Any,
    val getterFunction: kotlin.reflect.KFunction<*>,
  )

  fun introspect(
    objectGetterClass: KClass<*>,
    componentResolver: AutoMapComponentResolver,
  ): IntrospectedGetter {
    val contract = introspectContract(objectGetterClass)
    val instance =
      componentResolver.resolveOrNull(objectGetterClass)
        .unwrapElseError {
          "Object Instance must be present for class $objectGetterClass unless it is an " +
            "@ApplicationScoped CDI bean"
        }

    return IntrospectedGetter(
      instance = instance,
      getterFunction = contract.getterFunction,
    )
  }

  fun introspectContract(objectGetterClass: KClass<*>): IntrospectedGetterContract {
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

    return IntrospectedGetterContract(
      getterFunction = getterFunction,
    )
  }

  fun requireCompatibility(
    objectGetterClass: KClass<*>,
    objectKClass: KClass<*>,
    idType: KType,
    introspectedGetter: IntrospectedGetterContract,
  ) {
    requireCompatibility(
      objectGetterClass = objectGetterClass,
      objectKClass = objectKClass,
      idType = idType,
      getterFunction = introspectedGetter.getterFunction,
    )
  }

  fun requireCompatibility(
    objectGetterClass: KClass<*>,
    objectKClass: KClass<*>,
    idType: KType,
    introspectedGetter: IntrospectedGetter,
  ) {
    requireCompatibility(
      objectGetterClass = objectGetterClass,
      objectKClass = objectKClass,
      idType = idType,
      getterFunction = introspectedGetter.getterFunction,
    )
  }

  private fun requireCompatibility(
    objectGetterClass: KClass<*>,
    objectKClass: KClass<*>,
    idType: KType,
    getterFunction: kotlin.reflect.KFunction<*>,
  ) {
    val entityClassType =
      KClass::class.createType(
        arguments = listOf(KTypeProjection.invariant(objectKClass.starProjectedType))
      )

    val entityClassParameterType =
      getterFunction
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
      getterFunction
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
    val returnType = getterFunction.returnType

    require(
      returnType.isSupertypeOf(expectedReturnType) ||
        returnType == expectedReturnType
    ) {
      "Return type of getter $objectGetterClass " +
        "(${returnType}) is not compatible with $expectedReturnType"
    }
  }
}
