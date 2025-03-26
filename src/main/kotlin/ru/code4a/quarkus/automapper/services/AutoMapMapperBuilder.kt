package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.converters.NotSpecifiedAutoMapTypeConverter
import ru.code4a.quarkus.automapper.exceptions.CannotUpdateEntityInEmptyFieldInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.FieldCannotBeNullInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.FieldCannotBeUpdatedInputAutomapperException
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldNamingStrategy
import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.meta.InputClassInfo
import ru.code4a.quarkus.automapper.meta.InputCreateFieldInfo
import ru.code4a.quarkus.automapper.meta.InputCreateInfo
import ru.code4a.quarkus.automapper.services.AutoMapConverterChainBuilder.AutoMapDynConverter
import ru.code4a.quarkus.automapper.meta.ObjectFieldByInput
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectByIdGetter
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectByInputUpdater
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectFieldByInputUpdater
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import ru.code4a.quarkus.automapper.utils.reflection.bean.KotlinBeanField
import ru.code4a.quarkus.automapper.utils.reflection.bean.getBeanGettersFields
import ru.code4a.quarkus.automapper.utils.reflection.bean.getBeanSettersFields
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.*

class AutoMapMapperBuilder {

  private class MappingDirection(
    val inputKClass: KClass<*>,
    val objectKClass: KClass<*>,
  )

  private fun buildObjectByInputUpdater(
    objectKClass: KClass<*>,
    autoMapObjectFromInputAnnotation: AutoMapObjectFromInput,
    inputAutomapKClass: KClass<*>,
    mapperKClass: KClass<*>
  ): ObjectByInputUpdater {
    val objectFieldGetters =
      objectKClass.getBeanGettersFields()

    val objectFieldSetters =
      objectKClass.getBeanSettersFields()

    val inputGetterFields =
      inputAutomapKClass.getBeanGettersFields()

    val objectFieldByInputUpdaters =
      mapperKClass
        .getBeanGettersFields()
        .filter { it.name != autoMapObjectFromInputAnnotation.idField }
        .map { mapperGetterField ->
          val autoMapFieldAnnotation =
            mapperGetterField
              .function
              .findAnnotations(AutoMapField::class)
              .firstOrNull()

          val inputGetterField =
            inputGetterFields
              .find {
                it.name == mapperGetterField.name
              }
              .unwrapElseError {
                "Cannot find field ${mapperGetterField.name} in $inputAutomapKClass (mapper $mapperKClass)"
              }

          // TODO: inputGetterField.function.validateCallAccess()

          val resolvedFieldNameLazy =
            lazy {
              autoMapFieldAnnotation
                ?.getNamingStrategyInstance()
                ?.getObjectFieldName(inputGetterField.name)
                ?: inputGetterField.name
            }

          val setterName =
            when {
              autoMapFieldAnnotation != null && autoMapFieldAnnotation.setterFieldName.isNotEmpty() ->
                autoMapFieldAnnotation.setterFieldName

              autoMapFieldAnnotation != null && autoMapFieldAnnotation.fieldName.isNotEmpty() ->
                autoMapFieldAnnotation.fieldName

              else -> resolvedFieldNameLazy.value
            }

          val getterName =
            when {
              autoMapFieldAnnotation != null && autoMapFieldAnnotation.getterFieldName.isNotEmpty() ->
                autoMapFieldAnnotation.getterFieldName

              autoMapFieldAnnotation != null && autoMapFieldAnnotation.fieldName.isNotEmpty() ->
                autoMapFieldAnnotation.fieldName

              else -> resolvedFieldNameLazy.value
            }

          val objectGetter =
            objectFieldGetters
              .find {
                it.name == getterName
              }
              ?: throw FieldCannotBeUpdatedInputAutomapperException(inputGetterField.name)

          // TODO: objectGetter.function.validateCallAccess()

          val objectSetter =
            objectFieldSetters
              .find {
                it.name == setterName
              }

          // TODO: objectSetter?.function?.validateCallAccess()

          // Validate if a field without a setter method is a valid-nested entity that can be updated.
          // The field must either have a setter method OR be a nested entity (marked with AutoMapEntityFromInput)
          // that supports nested updates. This prevents accidental omission of setter methods for regular fields
          // while allowing intentional nested entity updates.
          val updater =
            if (objectSetter == null) {

              require(
                objectGetter
                  .function
                  .returnType
                  .findAnnotations(AutoMapObjectFromInput::class)
                  .isNotEmpty()
              ) {
                "Missing required setter method '$setterName' for class '$objectKClass'. \n" +
                  "The field from input class '$inputAutomapKClass' cannot be updated because: \n" +
                  "1) It has no setter method and  \n" +
                  "2) It is not marked as a nested entity (AutoMapEntityFromInput).  \n" +
                  "Either add a setter method or mark the field as a nested entity if nested updates are intended."
              }

              val mapperGetter =
                if (autoMapFieldAnnotation == null || autoMapFieldAnnotation.mapper == Object::class) {
                  { inputValue: Any ->
                    inputValue::class
                  }
                } else {
                  { inputValue: Any ->
                    autoMapFieldAnnotation.mapper
                  }
                }

              ObjectFieldByInputUpdater { autoMapper: AutoMapper,
                                          allowedCreationObjectClasses: Set<KClass<*>>,
                                          allowedUpdateObjectClasses: Set<KClass<*>>,
                                          inputFieldGetter: KotlinBeanField,
                                          obj: Any,
                                          inputValue: Any? ->
                if (inputValue == null) {
                  throw FieldCannotBeNullInputAutomapperException(inputFieldGetter.name)
                }

                val existingValue =
                  objectGetter.function.call(obj)
                    ?: throw CannotUpdateEntityInEmptyFieldInputAutomapperException(inputFieldGetter.name)

                autoMapper
                  .internalUpdateObjectByInput(
                    mapperSpec = mapperGetter(inputValue),
                    allowedCreationObjectClasses = allowedCreationObjectClasses,
                    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                    input = inputValue,
                    obj = existingValue
                  )
              }
            } else {
              val entitySetterParameter = objectSetter.function.valueParameters[0]
              val setterRequiredParameterType = entitySetterParameter.type
              val setterRequiredParameterCanBeNullable = setterRequiredParameterType.isMarkedNullable

              val valueConverter =
                getValueConverterForField(
                  autoMapFieldAnnotation = autoMapFieldAnnotation,
                  fromType = inputGetterField.function.returnType,
                  toType = setterRequiredParameterType
                )

              ObjectFieldByInputUpdater { autoMapper: AutoMapper,
                                          allowedCreationObjectClasses: Set<KClass<*>>,
                                          allowedUpdateObjectClasses: Set<KClass<*>>,
                                          inputFieldGetter: KotlinBeanField,
                                          obj: Any,
                                          inputValue: Any? ->
                if (inputValue == null && !setterRequiredParameterCanBeNullable) {
                  throw FieldCannotBeNullInputAutomapperException(inputFieldGetter.name)
                }

                val entityValue =
                  valueConverter.convert(
                    autoMapper = autoMapper,
                    allowedCreationObjectClasses = allowedCreationObjectClasses,
                    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                    input = inputValue
                  )

                objectSetter.function.call(obj, entityValue)
              }
            }

          ObjectFieldByInput(
            inputGetterField = inputGetterField,
            updater = updater,
          )
        }

    return ObjectByInputUpdater { autoMapper: AutoMapper,
                                  allowedCreationObjectClasses: Set<KClass<*>>,
                                  allowedUpdateObjectClasses: Set<KClass<*>>,
                                  obj: Any,
                                  input: Any ->
      objectFieldByInputUpdaters.forEach { fieldUpdater ->
        val inputGetterField = fieldUpdater.inputGetterField

        if (true) { // TODO: isSetterCalled
          val inputValue = inputGetterField.function.call(input)

          fieldUpdater.updater.updateField(
            autoMapper = autoMapper,
            allowedCreationObjectClasses = allowedCreationObjectClasses,
            allowedUpdateObjectClasses = allowedUpdateObjectClasses,
            inputFieldGetter = inputGetterField,
            obj = obj,
            inputValue = inputValue
          )
        }
      }
    }
  }

  private fun AutoMapField.getNamingStrategyInstance(): AutoMapFieldNamingStrategy {
    val objInstance = namingStrategy.objectInstance

    if (objInstance == null) {
      error("NamingStrategy must define object")
    }

    return objInstance
  }

  private fun getValueConverterForField(
    autoMapFieldAnnotation: AutoMapField?,
    fromType: KType,
    toType: KType
  ): AutoMapDynConverter {
    return if (
      autoMapFieldAnnotation != null &&
      autoMapFieldAnnotation.typeConverter != NotSpecifiedAutoMapTypeConverter::class
    ) {
      val convertFunction =
        autoMapFieldAnnotation
          .typeConverter
          .declaredFunctions
          .find { function -> function.name == AutoMapTypeConverter<*, *>::convert.name }
          .unwrapElseError {
            "Function ${AutoMapTypeConverter<*, *>::convert.name} must be " +
              "present for ${autoMapFieldAnnotation.typeConverter}"
          }

      val convertFunctionFirstParameter =
        convertFunction
          .valueParameters
          .firstOrNull()
          .unwrapElseError {
            "Function convert first parameter must be present for ${autoMapFieldAnnotation.typeConverter}"
          }

      require(
        convertFunctionFirstParameter.type.isSupertypeOf(fromType)
          || convertFunctionFirstParameter.type == fromType
      ) {
        "First argument of converter ${autoMapFieldAnnotation.typeConverter} " +
          "(${convertFunctionFirstParameter.type}) is not compatible with $fromType"
      }

      require(
        convertFunction.returnType.isSubtypeOf(toType)
          || convertFunction.returnType == fromType
      ) {
        "Return type of converter ${autoMapFieldAnnotation.typeConverter} " +
          "(${convertFunction.returnType}) is not compatible with $toType"
      }

      val specifiedTypeConverter =
        autoMapFieldAnnotation.typeConverter.createInstance() as AutoMapTypeConverter<Any?, Any?>

      AutoMapDynConverter { autoMapper: AutoMapper,
                            allowedCreationObjectClasses: Set<KClass<*>>,
                            allowedUpdateObjectClasses: Set<KClass<*>>,
                            input: Any? ->
        specifiedTypeConverter.convert(input)
      }
    } else {
      AutoMapConverterChainBuilder.build(
        fromType = fromType,
        toType = toType
      )
    }
  }

  private fun getMappingDirectionFromMapperSpec(mapperSpecKClass: KClass<*>): MappingDirection {
    val autoMapperSpecTo =
      mapperSpecKClass
        .supertypes
        .find { type ->
          val classifier = type.classifier as? KClass<*>

          classifier == AutoMapperSpecTo::class
        }

    return if (autoMapperSpecTo != null) {
      val objectKClass =
        autoMapperSpecTo
          .arguments
          .firstOrNull()
          .unwrapElseError {
            "Argument in $autoMapperSpecTo must be present"
          }
          .type
          .unwrapElseError {
            "Argument of $autoMapperSpecTo must be a type"
          }
          .classifier
          .unwrapElseError {
            "Argument of $autoMapperSpecTo must be a class"
          } as KClass<*>

      MappingDirection(
        inputKClass = mapperSpecKClass,
        objectKClass = objectKClass
      )
    } else {
      val autoMapperSpecType =
        mapperSpecKClass
          .supertypes
          .find { type ->
            val classifier = type.classifier as? KClass<*>

            classifier == AutoMapperSpec::class
          }
          .unwrapElseError {
            "Mapper $mapperSpecKClass must have supertype AutoMapper or AutoMapperTo"
          }

      val inputKClass =
        autoMapperSpecType
          .arguments[0]
          .unwrapElseError {
            "Argument 0 in $autoMapperSpecType must be present"
          }
          .type
          .unwrapElseError {
            "Argument 0 of $autoMapperSpecType must be a type"
          }
          .classifier
          .unwrapElseError {
            "Argument 0 of $autoMapperSpecType must be a class"
          } as KClass<*>

      val objectKClass =
        autoMapperSpecType
          .arguments[1]
          .unwrapElseError {
            "Argument 1 in $autoMapperSpecType must be present"
          }
          .type
          .unwrapElseError {
            "Argument 1 of $autoMapperSpecType must be a type"
          }
          .classifier
          .unwrapElseError {
            "Argument 1 of $autoMapperSpecType must be a class"
          } as KClass<*>

      MappingDirection(
        inputKClass = inputKClass,
        objectKClass = objectKClass
      )
    }
  }

  fun build(mapperAutomapClasses: List<Class<*>>): AutoMapper {
    val inputClassesInfoByMapperSpecClass =
      mapperAutomapClasses.associate { mapperAutomapClass ->
        val mapperAutomapKClass = mapperAutomapClass.kotlin

        val mappingDirection =
          getMappingDirectionFromMapperSpec(mapperAutomapKClass)

        val objectKClass =
          mappingDirection.objectKClass

        val inputGettersFields =
          mappingDirection
            .inputKClass
            .getBeanGettersFields()

        val autoMapObjectFromInputAnnotation =
          mapperAutomapKClass
            .findAnnotations(AutoMapObjectFromInput::class)
            .firstOrNull()
            .unwrapElseError {
              "Mapper class $mapperAutomapKClass must be annotated with @${AutoMapObjectFromInput::class}"
            }

        val inputCreateInfo =
          if (autoMapObjectFromInputAnnotation.allowCreate) {
            val entityCompanionObjectClass =
              objectKClass.companionObject
                ?: error("Entity class $objectKClass should have Companion object for construct")

            val constructMethod =
              entityCompanionObjectClass
                .declaredFunctions
                .find {
                  it.name == autoMapObjectFromInputAnnotation.constructMethod
                }
                .unwrapElseError {
                  "Cannot find method ${autoMapObjectFromInputAnnotation.constructMethod} " +
                    "inside object cass $entityCompanionObjectClass"
                }

            // TODO: constructMethod.validateCallAccess()

            val createFields =
              mapperAutomapKClass
                .getBeanGettersFields()
                .filter { autoMapObjectFromInputAnnotation.idField != it.name }
                .map { mapperGetterField ->
                  val inputGetterField =
                    inputGettersFields
                      .find { field ->
                        field.name == mapperGetterField.name
                      }
                      .unwrapElseError {
                        "Cannot find getter field for ${mapperGetterField.name}. " +
                          "\nMapper: $mapperAutomapKClass\n" +
                          "Input ${mappingDirection.inputKClass}\n" +
                          "Output: ${mappingDirection.objectKClass}"
                      }

                  // TODO: inputGetterField.function.validateCallAccess()

                  val annotation =
                    mapperGetterField
                      .function
                      .findAnnotations(AutoMapField::class)
                      .firstOrNull()

                  val constructParameterName =
                    when {
                      annotation?.constructParameterName?.isNotEmpty() == true ->
                        annotation.constructParameterName

                      annotation != null && annotation.fieldName.isNotEmpty() ->
                        annotation.fieldName

                      annotation != null ->
                        annotation
                          .getNamingStrategyInstance()
                          .getObjectFieldName(inputGetterField.name)

                      else -> inputGetterField.name
                    }

                  val constructParameter =
                    constructMethod
                      .valueParameters
                      .find { it.name == constructParameterName }
                      .unwrapElseError {
                        "Cannot find parameter \"$constructParameterName\" in constructor of " +
                          "$objectKClass (mapper $mapperAutomapKClass, input ${mappingDirection.inputKClass})"
                      }

                  InputCreateFieldInfo(
                    constructParameterName = constructParameterName,
                    inputFieldGetter = inputGetterField,
                    converter =
                      getValueConverterForField(
                        autoMapFieldAnnotation = annotation,
                        fromType = inputGetterField.function.returnType,
                        toType = constructParameter.type
                      )
                  )
                }

            constructMethod
              .valueParameters
              .filter { parameter -> parameter.isOptional == false }
              .forEach { parameter ->
                createFields
                  .find { info ->
                    info.constructParameterName == parameter.name
                  }
                  .unwrapElseError {
                    "Cannot find required field \"${parameter}\" in \n${mappingDirection.inputKClass} for construct " +
                      "\n$objectKClass (mapper \n$mapperAutomapKClass)"
                  }
              }

            InputCreateInfo(
              constructorObject = entityCompanionObjectClass.objectInstance,
              constructMethod = constructMethod,
              createFields = createFields,
              createFieldsByName = createFields.associateBy { it.constructParameterName }
            )
          } else {
            null
          }

        val objectByIdGetter =
          autoMapObjectFromInputAnnotation.let { autoMapEntityFromInputAnnotation ->
            if (autoMapEntityFromInputAnnotation.objectGetterClass == Object::class) {
              null
            } else {
              require(
                autoMapEntityFromInputAnnotation
                  .objectGetterClass
                  .declaredFunctions
                  .size == 1
              ) {
                "Entity getter class ${autoMapEntityFromInputAnnotation.objectGetterClass} must " +
                  "have only one declared function"
              }

              val declaredFunction =
                autoMapEntityFromInputAnnotation
                  .objectGetterClass
                  .declaredFunctions
                  .firstOrNull()
                  .unwrapElseError {
                    "Entity getter class ${autoMapEntityFromInputAnnotation.objectGetterClass} must " +
                      "have declared function"
                  }

              require(declaredFunction.parameters.size == 3) {
                "Declared function $declaredFunction of " +
                  "entity getter class ${autoMapEntityFromInputAnnotation.objectGetterClass} " +
                  "must have 3 parameters"
              }

              val objectGetterInstance =
                autoMapEntityFromInputAnnotation
                  .objectGetterClass
                  .objectInstance
                  .unwrapElseError {
                    "Object Instance must be present for class ${autoMapEntityFromInputAnnotation.objectGetterClass}"
                  }

              // TODO: declaredFunction.validateCallAccess()

              ObjectByIdGetter { id: Any ->
                declaredFunction.call(
                  objectGetterInstance,
                  objectKClass,
                  id
                )
              }
            }
          }

        val objectByInputUpdater =
          if (autoMapObjectFromInputAnnotation.allowUpdate) {
            buildObjectByInputUpdater(
              objectKClass = objectKClass,
              autoMapObjectFromInputAnnotation = autoMapObjectFromInputAnnotation,
              inputAutomapKClass = mappingDirection.inputKClass,
              mapperKClass = mapperAutomapKClass,
            )
          } else {
            null
          }

        val idGetterField =
          mapperAutomapKClass
            .getBeanGettersFields()
            .find {
              it.name == autoMapObjectFromInputAnnotation.idField
            }
            ?.let { field ->
              inputGettersFields
                .find {
                  it.name == field.name
                }
                .unwrapElseError {
                  "Cannot find field ${field.name} in ${mappingDirection.inputKClass}"
                }
            }

        mapperAutomapClass to InputClassInfo(
          objectByInputUpdater = objectByInputUpdater,
          autoMapObjectFromInputAnnotation = autoMapObjectFromInputAnnotation,
          idGetterField = idGetterField,
          inputCreateInfo = inputCreateInfo,
          objectByIdGetter = objectByIdGetter,
          objectKClass = objectKClass,
          inputKClass = mappingDirection.inputKClass,
        )
      }

    return AutoMapper(
      inputClassesInfoByMapperSpecClass = inputClassesInfoByMapperSpecClass
    )
  }
}
