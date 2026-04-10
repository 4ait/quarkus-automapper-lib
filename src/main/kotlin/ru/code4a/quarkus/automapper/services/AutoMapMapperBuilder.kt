package ru.code4a.quarkus.automapper.services

import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.converters.NotSpecifiedAutoMapTypeConverter
import ru.code4a.quarkus.automapper.exceptions.CannotUpdateEntityInEmptyFieldInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.FieldCannotBeNullInputAutomapperException
import ru.code4a.quarkus.automapper.exceptions.FieldCannotBeUpdatedInputAutomapperException
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldNamingStrategy
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldUpdateValidator
import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.meta.AutoMapFieldUpdateValidatorInfo
import ru.code4a.quarkus.automapper.meta.InputClassInfo
import ru.code4a.quarkus.automapper.meta.InputCreateFieldInfo
import ru.code4a.quarkus.automapper.meta.InputCreateInfo
import ru.code4a.quarkus.automapper.services.AutoMapConverterChainBuilder.AutoMapDynConverter
import ru.code4a.quarkus.automapper.meta.ObjectFieldByInput
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectByIdGetter
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectByInputUpdater
import ru.code4a.quarkus.automapper.meta.interfaces.ObjectFieldByInputUpdater
import ru.code4a.quarkus.automapper.utils.nullable.unwrapElseError
import ru.code4a.quarkus.automapper.utils.reflection.getReadableName
import ru.code4a.quarkus.automapper.utils.reflection.bean.KotlinBeanField
import ru.code4a.quarkus.automapper.utils.reflection.bean.getBeanGettersFields
import ru.code4a.quarkus.automapper.utils.reflection.bean.getBeanSettersFields
import ru.code4a.quarkus.automapper.validators.NotSpecifiedAutoMapFieldUpdateValidator
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.*

class AutoMapMapperBuilder {

  private class MappingDirection(
    val inputKClass: KClass<*>,
    val objectKClass: KClass<*>,
  )

  private class ResolvedFieldNames(
    val setterName: String,
    val getterName: String,
    val constructParameterName: String,
  )

  private class FieldUpdateValidatorGenericTypes(
    val parentType: KType,
    val currentType: KType,
    val newType: KType,
    val inputType: KType,
  )

  private fun buildObjectByInputUpdater(
    objectKClass: KClass<*>,
    autoMapObjectFromInputAnnotation: AutoMapObjectFromInput,
    inputAutomapKClass: KClass<*>,
    mapperKClass: KClass<*>
  ): ObjectByInputUpdater {
    val inputClassName = inputAutomapKClass.getReadableName()

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

          val resolvedFieldNames =
            resolveFieldNames(
              autoMapFieldAnnotation = autoMapFieldAnnotation,
              inputFieldName = inputGetterField.name
            )

          val objectGetter =
            objectFieldGetters
              .find {
                it.name == resolvedFieldNames.getterName
              }
              ?: throw FieldCannotBeUpdatedInputAutomapperException(
                fieldName = inputGetterField.name,
                className = inputClassName
              )

          // TODO: objectGetter.function.validateCallAccess()

          val objectSetter =
            objectFieldSetters
              .find {
                it.name == resolvedFieldNames.setterName
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
                "Missing required setter method '${resolvedFieldNames.setterName}' for class '$objectKClass'. \n" +
                  "The field from input class '$inputAutomapKClass' cannot be updated because: \n" +
                  "1) It has no setter method and  \n" +
                  "2) It is not marked as a nested entity (AutoMapEntityFromInput).  \n" +
                  "Either add a setter method or mark the field as a nested entity if nested updates are intended."
              }

              require(isFieldUpdateValidatorSpecified(autoMapFieldAnnotation).not()) {
                "Field update validator ${autoMapFieldAnnotation?.updateValidatorClass} cannot be used for " +
                  "field '${inputGetterField.name}' in mapper $mapperKClass because target field " +
                  "'${resolvedFieldNames.getterName}' is updated in-place without a setter. " +
                  "Update validators require a stable current/new value boundary. " +
                  "Add a setter or remove the update validator."
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
                  throw FieldCannotBeNullInputAutomapperException(
                    fieldName = inputFieldGetter.name,
                    className = inputClassName
                  )
                }

                val existingValue =
                  objectGetter.function.call(obj)
                    ?: throw CannotUpdateEntityInEmptyFieldInputAutomapperException(
                      fieldName = inputFieldGetter.name,
                      className = inputClassName
                    )

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
              val fieldUpdateValidatorInfo =
                getFieldUpdateValidatorInfo(
                  autoMapFieldAnnotation = autoMapFieldAnnotation,
                  fieldName = inputGetterField.name,
                  mapperKClass = mapperKClass,
                  parentKClass = objectKClass,
                  currentType = objectGetter.function.returnType,
                  newType = setterRequiredParameterType,
                  inputType = inputGetterField.function.returnType,
                )

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
                  throw FieldCannotBeNullInputAutomapperException(
                    fieldName = inputFieldGetter.name,
                    className = inputClassName
                  )
                }

                val currentValue =
                  objectGetter.function.call(obj)

                val entityValue =
                  valueConverter.convert(
                    autoMapper = autoMapper,
                    allowedCreationObjectClasses = allowedCreationObjectClasses,
                    allowedUpdateObjectClasses = allowedUpdateObjectClasses,
                    input = inputValue
                  )

                fieldUpdateValidatorInfo?.validate(
                  parent = obj,
                  currentValue = currentValue,
                  newValue = entityValue,
                  inputValue = inputValue,
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

  private fun resolveFieldNames(
    autoMapFieldAnnotation: AutoMapField?,
    inputFieldName: String,
  ): ResolvedFieldNames {
    val resolvedFieldName =
      autoMapFieldAnnotation
        ?.let { annotation ->
          when {
            annotation.fieldName.isNotEmpty() -> annotation.fieldName
            else -> annotation.getNamingStrategyInstance().getObjectFieldName(inputFieldName)
          }
        }
        ?: inputFieldName

    val setterName =
      when {
        autoMapFieldAnnotation?.setterFieldName?.isNotEmpty() == true ->
          autoMapFieldAnnotation.setterFieldName

        else -> resolvedFieldName
      }

    val getterName =
      when {
        autoMapFieldAnnotation?.getterFieldName?.isNotEmpty() == true ->
          autoMapFieldAnnotation.getterFieldName

        else -> resolvedFieldName
      }

    val constructParameterName =
      when {
        autoMapFieldAnnotation?.constructParameterName?.isNotEmpty() == true ->
          autoMapFieldAnnotation.constructParameterName

        else -> resolvedFieldName
      }

    return ResolvedFieldNames(
      setterName = setterName,
      getterName = getterName,
      constructParameterName = constructParameterName,
    )
  }

  private fun AutoMapField.getNamingStrategyInstance(): AutoMapFieldNamingStrategy {
    val objInstance = namingStrategy.objectInstance

    if (objInstance == null) {
      error("NamingStrategy must define object")
    }

    return objInstance
  }

  private fun isFieldUpdateValidatorSpecified(autoMapFieldAnnotation: AutoMapField?): Boolean {
    return autoMapFieldAnnotation != null &&
      autoMapFieldAnnotation.updateValidatorClass != NotSpecifiedAutoMapFieldUpdateValidator::class
  }

  private fun getFieldUpdateValidatorGenericTypes(
    updateValidatorClass: KClass<out AutoMapFieldUpdateValidator<*, *, *, *>>
  ): FieldUpdateValidatorGenericTypes {
    val updateValidatorSuperType =
      updateValidatorClass
        .allSupertypes
        .find { type ->
          (type.classifier as? KClass<*>) == AutoMapFieldUpdateValidator::class
        }
        .unwrapElseError {
          "Update validator $updateValidatorClass must implement ${AutoMapFieldUpdateValidator::class}"
        }

    fun getArgument(index: Int, name: String): KType {
      return updateValidatorSuperType
        .arguments
        .getOrNull(index)
        ?.type
        .unwrapElseError {
          "Update validator $updateValidatorClass must declare a concrete $name generic type"
        }
    }

    return FieldUpdateValidatorGenericTypes(
      parentType = getArgument(0, "parent"),
      currentType = getArgument(1, "current"),
      newType = getArgument(2, "new"),
      inputType = getArgument(3, "input"),
    )
  }

  private fun KType.normalizeForValidatorCompatibility(): KType {
    return withNullability(false)
  }

  private fun requireFieldUpdateValidatorTypeCompatibility(
    updateValidatorClass: KClass<out AutoMapFieldUpdateValidator<*, *, *, *>>,
    mapperKClass: KClass<*>,
    fieldName: String,
    role: String,
    updateValidatorType: KType,
    actualType: KType,
  ) {
    val normalizedUpdateValidatorType = updateValidatorType.normalizeForValidatorCompatibility()
    val normalizedActualType = actualType.normalizeForValidatorCompatibility()

    require(normalizedUpdateValidatorType.isSupertypeOf(normalizedActualType)) {
      "Update validator $updateValidatorClass is not compatible with field '$fieldName' in mapper $mapperKClass: " +
        "$role type $updateValidatorType is not compatible with actual type $actualType"
    }
  }

  private fun getFieldUpdateValidatorInfo(
    autoMapFieldAnnotation: AutoMapField?,
    fieldName: String,
    mapperKClass: KClass<*>,
    parentKClass: KClass<*>,
    currentType: KType,
    newType: KType,
    inputType: KType,
  ): AutoMapFieldUpdateValidatorInfo? {
    if (isFieldUpdateValidatorSpecified(autoMapFieldAnnotation).not()) {
      return null
    }

    val updateValidatorClass =
      autoMapFieldAnnotation
        ?.updateValidatorClass
        .unwrapElseError {
          "Update validator must be present for field '$fieldName' in mapper $mapperKClass"
        }

    val genericTypes =
      getFieldUpdateValidatorGenericTypes(updateValidatorClass)

    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass = updateValidatorClass,
      mapperKClass = mapperKClass,
      fieldName = fieldName,
      role = "parent",
      updateValidatorType = genericTypes.parentType,
      actualType = parentKClass.starProjectedType,
    )
    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass = updateValidatorClass,
      mapperKClass = mapperKClass,
      fieldName = fieldName,
      role = "currentValue",
      updateValidatorType = genericTypes.currentType,
      actualType = currentType,
    )
    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass = updateValidatorClass,
      mapperKClass = mapperKClass,
      fieldName = fieldName,
      role = "newValue",
      updateValidatorType = genericTypes.newType,
      actualType = newType,
    )
    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass = updateValidatorClass,
      mapperKClass = mapperKClass,
      fieldName = fieldName,
      role = "inputValue",
      updateValidatorType = genericTypes.inputType,
      actualType = inputType,
    )

    return AutoMapFieldUpdateValidatorInfo(
      fieldName = fieldName,
      validator =
        updateValidatorClass.objectInstance.unwrapElseError {
          "Update validator $updateValidatorClass should be object instance of AutoMapFieldUpdateValidator"
        } as AutoMapFieldUpdateValidator<Any, Any?, Any?, Any?>,
    )
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
      val introspectedConverter =
        AutoMapTypeConverterIntrospector.introspect(
          autoMapFieldAnnotation.typeConverter
        )

      AutoMapTypeConverterIntrospector.requireCompatibility(
        typeConverterClass = autoMapFieldAnnotation.typeConverter,
        fromType = fromType,
        toType = toType,
        introspectedConverter = introspectedConverter
      )

      val specifiedTypeConverter = introspectedConverter.instance

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

                  val resolvedFieldNames =
                    resolveFieldNames(
                      autoMapFieldAnnotation = annotation,
                      inputFieldName = inputGetterField.name
                    )

                  val constructParameter =
                    constructMethod
                      .valueParameters
                      .find { it.name == resolvedFieldNames.constructParameterName }
                      .unwrapElseError {
                        "Cannot find parameter \"${resolvedFieldNames.constructParameterName}\" in constructor of " +
                          "$objectKClass (mapper $mapperAutomapKClass, input ${mappingDirection.inputKClass})"
                      }
                  InputCreateFieldInfo(
                    constructParameterName = resolvedFieldNames.constructParameterName,
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

        val objectByIdGetter =
          autoMapObjectFromInputAnnotation.let { autoMapEntityFromInputAnnotation ->
            if (autoMapEntityFromInputAnnotation.objectGetterClass == Object::class) {
              null
            } else {
              val introspectedGetter =
                AutoMapObjectGetterIntrospector.introspect(
                  autoMapEntityFromInputAnnotation.objectGetterClass
                )

              idGetterField?.let { field ->
                AutoMapObjectGetterIntrospector.requireCompatibility(
                  objectGetterClass = autoMapEntityFromInputAnnotation.objectGetterClass,
                  objectKClass = objectKClass,
                  idType = field.function.returnType,
                  introspectedGetter = introspectedGetter,
                )
              }

              // TODO: introspectedGetter.getterFunction.validateCallAccess()

              ObjectByIdGetter { id: Any ->
                introspectedGetter.getterFunction.call(
                  introspectedGetter.instance,
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
