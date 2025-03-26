package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.exceptions.FieldCannotBeNullInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.FieldIsNotSupportedForCreateInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.MissingRequiredFieldInputAutomapperException
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.meta.InputClassInfo
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import kotlin.reflect.KClass
import kotlin.reflect.KParameter

class AutoMapper(
  private val inputClassesInfoByMapperSpecClass: Map<Class<*>, InputClassInfo>
) {

  fun <TO : Any, FROM : Any, T : AutoMapperSpec<FROM, TO>> createOrUpdateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: FROM
  ): TO {
    return internalCreateOrUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input
    ) as TO
  }

  fun <TO : Any, T : AutoMapperSpecTo<TO>> createOrUpdateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: T
  ): TO {
    return internalCreateOrUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input
    ) as TO
  }

  fun <TO : Any, FROM : Any, T : AutoMapperSpec<FROM, TO>> updateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: FROM,
    obj: TO
  ) {
    internalUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input,
      obj = obj
    )
  }

  fun <TO : Any, T : AutoMapperSpecTo<TO>> updateObjectByInput(
    mapperSpec: KClass<T>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: T,
    obj: TO
  ) {
    internalUpdateObjectByInput(
      mapperSpec = mapperSpec,
      allowedCreationObjectClasses = allowedCreationObjectClasses,
      allowedUpdateObjectClasses = allowedUpdateObjectClasses,
      input = input,
      obj = obj
    )
  }

  /**
   * Creates or updates an object based on the input.
   *
   * @param allowedCreationObjectClasses The set of allowed creation entity classes.
   * @param allowedUpdateObjectClasses The set of allowed update entity classes.
   * @param input The input object.
   * @return The created or updated entity or object.
   */
  internal fun internalCreateOrUpdateObjectByInput(
    mapperSpec: KClass<*>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: Any
  ): Any {
    val inputClassInfo =
      inputClassesInfoByMapperSpecClass[mapperSpec.java]
        ?: error("Cannot find input info for mapper spec ${mapperSpec.java}")

    val autoMapObjectFromInputAnnotation = inputClassInfo.autoMapObjectFromInputAnnotation

    val idGetterField = inputClassInfo.idGetterField

    val id = idGetterField?.function?.call(input)

    val result =
      if (id != null) {
        val entity =
          inputClassInfo
            .objectByIdGetter
            .unwrapElseError { "Input ${input::class} must have object by id getter" }
            .getObject(
              id
            )
            .unwrapElseError {
              "Object ${inputClassInfo.objectKClass} with ID " +
                "$id not found when attempting to update. " +
                "Please verify the ID exists and you have permission to access it."
            }

        if (autoMapObjectFromInputAnnotation.allowUpdate) {
          internalUpdateObjectByInput(
            mapperSpec,
            allowedCreationObjectClasses,
            allowedUpdateObjectClasses,
            input,
            entity
          )
        }

        entity
      } else {
        val objectKClass = inputClassInfo.objectKClass

        if (objectKClass !in allowedCreationObjectClasses) {
          error("Create class $objectKClass is not allowed")
        }

        constructObjectFromInput(
          allowedCreationObjectClasses = allowedCreationObjectClasses,
          allowedUpdateObjectClasses = allowedUpdateObjectClasses,
          inputClassInfo = inputClassInfo,
          input = input
        )
      }

    return result
  }

  /**
   * Update the entity based on the input.
   *
   * @param allowedCreationObjectClasses The set of allowed creation entity classes.
   * @param allowedUpdateObjectClasses The set of allowed update entity classes.
   * @param input The input object.
   * @param obj The entity object to update.
   * @throws RuntimeException If access to update the entity is denied.
   * @throws FieldCannotBeNullInputAutomapperException If a field cannot be null.
   */
  internal fun internalUpdateObjectByInput(
    mapperSpec: KClass<*>,
    allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
    allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
    input: Any,
    obj: Any
  ) {
    if (obj::class !in allowedUpdateObjectClasses) {
      error("Access to update ${obj::class} is denied")
    }

    val inputClassInfo = inputClassesInfoByMapperSpecClass[mapperSpec.java]
      ?: error("Cannot find input info for mapper spec ${mapperSpec.java}")

    inputClassInfo
      .objectByInputUpdater
      .unwrapElseError {
        "Object ${obj::class} cannot be updated by configuration"
      }
      .updateObj(
        autoMapper = this,
        allowedUpdateObjectClasses = allowedUpdateObjectClasses,
        allowedCreationObjectClasses = allowedCreationObjectClasses,
        obj = obj,
        input = input
      )
  }

  private fun constructObjectFromInput(
    allowedCreationObjectClasses: Set<KClass<*>>,
    allowedUpdateObjectClasses: Set<KClass<*>>,
    inputClassInfo: InputClassInfo,
    input: Any
  ): Any {
    val inputCreateInfo =
      inputClassInfo
        .inputCreateInfo
        .unwrapElseError {
          "Object ${inputClassInfo.objectKClass} cannot be created by the configuration"
        }

    val notUsedInputPropertiesByBuildFieldNameSet = inputCreateInfo.createFieldsByName.toMutableMap()

    val methodArgs = mutableMapOf<KParameter, Any?>()

    for (objectCreateParameter in inputCreateInfo.constructMethod.parameters) {
      if (objectCreateParameter.kind == KParameter.Kind.INSTANCE) {
        methodArgs[objectCreateParameter] = inputCreateInfo.constructorObject
        continue
      }

      val createFieldInfo =
        inputCreateInfo
          .createFieldsByName[objectCreateParameter.name]

      val inputFieldGetter =
        createFieldInfo?.inputFieldGetter

      if (inputFieldGetter == null) {
        if (!objectCreateParameter.isOptional) {
          error(
            "Cannot find input property \"${objectCreateParameter}\" inside input class \"${input::class}\" " +
              "for build method \"${inputCreateInfo.constructMethod.name}\" of ${inputClassInfo.objectKClass}"
          )
        }

        continue
      }

      notUsedInputPropertiesByBuildFieldNameSet
        .remove(
          objectCreateParameter.name
        )

      if (true) { // TODO: isSetterCalled
        val value = inputFieldGetter.function.call(input)

        if (value == null && !objectCreateParameter.type.isMarkedNullable) {
          throw FieldCannotBeNullInputAutomapperException(inputFieldGetter.name)
        }

        methodArgs[objectCreateParameter] =
          createFieldInfo.converter.convert(
            autoMapper = this,
            allowedUpdateObjectClasses = allowedUpdateObjectClasses,
            allowedCreationObjectClasses = allowedCreationObjectClasses,
            input = value
          )
      } else if (!objectCreateParameter.isOptional) {
        throw MissingRequiredFieldInputAutomapperException(inputFieldGetter.name)
      }
    }

    notUsedInputPropertiesByBuildFieldNameSet.forEach { (t, inputCreateFieldInfo) ->
      if (true) { // TODO: isSetterCalled
        throw FieldIsNotSupportedForCreateInputAutomapperException(inputCreateFieldInfo.inputFieldGetter.name)
      }
    }

    val entity =
      inputCreateInfo.constructMethod.callBy(methodArgs)
        ?: error("Object should be present after creation")

    return entity
  }
}

inline fun <TO : Any, reified T : AutoMapperSpecTo<TO>> AutoMapper.createOrUpdateObjectByInput(
  allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
  allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
  input: T
): TO {
  return createOrUpdateObjectByInput(
    mapperSpec = T::class,
    allowedCreationObjectClasses = allowedCreationObjectClasses,
    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
    input = input,
  ) as TO
}

inline fun <TO : Any, reified T : AutoMapperSpecTo<TO>> AutoMapper.updateObjectByInput(
  allowedCreationObjectClasses: Set<KClass<*>> = emptySet(),
  allowedUpdateObjectClasses: Set<KClass<*>> = emptySet(),
  input: T,
  obj: TO
) {
  updateObjectByInput(
    mapperSpec = T::class,
    allowedCreationObjectClasses = allowedCreationObjectClasses,
    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
    input = input,
    obj = obj
  )
}
