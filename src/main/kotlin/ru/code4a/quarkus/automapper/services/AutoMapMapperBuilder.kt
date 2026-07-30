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
import ru.code4a.quarkus.automapper.meta.BatchExistingEntityLocatorInfo
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
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.*

class AutoMapMapperBuilder private constructor(
  private val componentResolver: AutoMapComponentResolver,
  private val prevalidatedLocatorKinds: Map<KClass<*>, AutoMapExistingEntityLocatorKind>?,
  private val defaultConverterClassNames: List<String>?,
) {

  constructor() : this(AutoMapComponentResolver(), null, null)

  internal constructor(cdiLookup: (KClass<*>) -> Any?) :
    this(AutoMapComponentResolver(cdiLookup), null, null)

  internal constructor(
    cdiLookup: (KClass<*>) -> Any?,
    prevalidatedLocatorKinds: Map<KClass<*>, AutoMapExistingEntityLocatorKind>,
    defaultConverterClassNames: List<String>? = null,
  ) : this(
    AutoMapComponentResolver(cdiLookup),
    prevalidatedLocatorKinds,
    defaultConverterClassNames,
  )

  private val defaultConverters by lazy {
    AutoMapTypeDefaultConverters(componentResolver)
  }

  private val defaultConvertersBlueprint by lazy {
    AutoMapTypeDefaultConvertersBlueprint(defaultConverterClassNames)
  }

  private var activeBlueprintComponentReferences: AutoMapComponentReferences? = null

  private fun blueprintComponentReferences(): AutoMapComponentReferences {
    return checkNotNull(activeBlueprintComponentReferences) {
      "CDI component references can only be prepared while a mapper blueprint is being built"
    }
  }

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
                                          mappingContext: AutoMapMappingFrame,
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
                    obj = existingValue,
                    parentContext = mappingContext,
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
                                          mappingContext: AutoMapMappingFrame,
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
                    mappingContext = mappingContext,
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
                                  mappingContext: AutoMapMappingFrame,
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
            mappingContext = mappingContext,
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
    return componentResolver.resolveOrNull(namingStrategy)
      ?: error(
        "NamingStrategy must define object or be an @ApplicationScoped CDI bean: $namingStrategy"
      )
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
        componentResolver.resolveOrNull(updateValidatorClass).unwrapElseError {
          "Update validator $updateValidatorClass should be object instance of AutoMapFieldUpdateValidator " +
            "or an @ApplicationScoped CDI bean"
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
          typeConverterClass = autoMapFieldAnnotation.typeConverter,
          componentResolver = componentResolver,
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
                            mappingContext: AutoMapMappingFrame,
                            input: Any? ->
        specifiedTypeConverter.convert(input)
      }
    } else {
      AutoMapConverterChainBuilder.build(
        fromType = fromType,
        toType = toType,
        defaultConverters = defaultConverters,
        mapperSpec =
          autoMapFieldAnnotation
            ?.mapper
            ?.takeUnless { mapper -> mapper == Object::class },
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

  private fun validateExistingEntityLocatorParentTypes(
    inputClassesInfoByMapperSpecClass: Map<Class<*>, InputClassInfo>,
  ) {
    inputClassesInfoByMapperSpecClass.forEach { (parentMapperClass, parentInfo) ->
      parentMapperClass.kotlin.getBeanGettersFields().forEach fieldLoop@{ mapperField ->
        val fieldAnnotation = mapperField.function.findAnnotations(AutoMapField::class).firstOrNull()
        val nestedMapperClass =
          if (fieldAnnotation != null && fieldAnnotation.mapper != Object::class) {
            fieldAnnotation.mapper
          } else {
            nestedMappedClass(mapperField.function.returnType)
          }
        val childInfo =
          nestedMapperClass
            ?.let { inputClassesInfoByMapperSpecClass[it.java] }
            ?: return@fieldLoop

        childInfo.existingEntityLocators.forEach { locator ->
          require(
            locator.parentSourceType!!
              .withNullability(false)
              == parentInfo.inputKClass.starProjectedType
          ) {
            "Existing entity lookup ${locator.locatorClass} parent source type " +
              "${locator.parentSourceType} is not compatible with ${parentInfo.inputKClass}"
          }
          require(
            locator.parentTargetType!!
              .withNullability(false)
              == parentInfo.objectKClass.starProjectedType
          ) {
            "Existing entity lookup ${locator.locatorClass} parent target type " +
              "${locator.parentTargetType} is not compatible with ${parentInfo.objectKClass}"
          }
        }
      }
    }
  }

  private fun nestedMappedClass(type: KType): KClass<*>? {
    val typeClass = type.classifier as? KClass<*> ?: return null
    return if (typeClass.isSubclassOf(Collection::class)) {
      type.arguments.firstOrNull()?.type?.let(::nestedMappedClass)
    } else {
      typeClass.takeIf { it.findAnnotations(AutoMapObjectFromInput::class).isNotEmpty() }
    }
  }

  private fun resolveFieldNamesBlueprint(
    autoMapFieldAnnotation: AutoMapField?,
    inputFieldName: String,
  ): AutoMapBinding<ResolvedFieldNames> {
    if (autoMapFieldAnnotation == null) {
      return AutoMapBinding.fixed(
        ResolvedFieldNames(inputFieldName, inputFieldName, inputFieldName)
      )
    }

    val resolvedNameBinding =
      if (autoMapFieldAnnotation.fieldName.isNotEmpty()) {
        AutoMapBinding.fixed(autoMapFieldAnnotation.fieldName)
      } else {
        @Suppress("UNCHECKED_CAST")
        val strategyClass =
          autoMapFieldAnnotation.namingStrategy as KClass<AutoMapFieldNamingStrategy>
        componentBinding(
          componentClass = strategyClass,
          missingMessage = {
            "NamingStrategy must define object or be an @ApplicationScoped CDI bean: $strategyClass"
          },
        ).map { strategy -> strategy.getObjectFieldName(inputFieldName) }
      }

    return resolvedNameBinding.map { resolvedName ->
      ResolvedFieldNames(
        setterName = autoMapFieldAnnotation.setterFieldName.ifEmpty { resolvedName },
        getterName = autoMapFieldAnnotation.getterFieldName.ifEmpty { resolvedName },
        constructParameterName = autoMapFieldAnnotation.constructParameterName.ifEmpty { resolvedName },
      )
    }
  }

  private fun getFieldUpdateValidatorInfoBlueprint(
    autoMapFieldAnnotation: AutoMapField?,
    fieldName: String,
    mapperKClass: KClass<*>,
    parentKClass: KClass<*>,
    currentType: KType,
    newType: KType,
    inputType: KType,
  ): AutoMapBinding<AutoMapFieldUpdateValidatorInfo?> {
    if (!isFieldUpdateValidatorSpecified(autoMapFieldAnnotation)) {
      return AutoMapBinding.fixed(null)
    }

    val updateValidatorClass =
      autoMapFieldAnnotation?.updateValidatorClass.unwrapElseError {
        "Update validator must be present for field '$fieldName' in mapper $mapperKClass"
      }
    val genericTypes = getFieldUpdateValidatorGenericTypes(updateValidatorClass)

    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass,
      mapperKClass,
      fieldName,
      "parent",
      genericTypes.parentType,
      parentKClass.starProjectedType,
    )
    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass,
      mapperKClass,
      fieldName,
      "currentValue",
      genericTypes.currentType,
      currentType,
    )
    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass,
      mapperKClass,
      fieldName,
      "newValue",
      genericTypes.newType,
      newType,
    )
    requireFieldUpdateValidatorTypeCompatibility(
      updateValidatorClass,
      mapperKClass,
      fieldName,
      "inputValue",
      genericTypes.inputType,
      inputType,
    )

    @Suppress("UNCHECKED_CAST")
    val typedValidatorClass =
      updateValidatorClass as KClass<AutoMapFieldUpdateValidator<Any, Any?, Any?, Any?>>
    val validatorReference =
      blueprintComponentReferences().reference(
        componentClass = typedValidatorClass,
        missingMessage = {
          "Update validator $updateValidatorClass should be an object instance or an @ApplicationScoped CDI bean"
        },
      )
    val adapter =
      object : AutoMapFieldUpdateValidator<Any, Any?, Any?, Any?> {
        override fun validate(
          parent: Any,
          currentValue: Any?,
          newValue: Any?,
          inputValue: Any?,
          fieldName: String,
        ) {
          validatorReference.get().validate(parent, currentValue, newValue, inputValue, fieldName)
        }
      }
    return AutoMapBinding.fixed(
      AutoMapFieldUpdateValidatorInfo(fieldName = fieldName, validator = adapter)
    )
  }

  private fun getValueConverterBlueprint(
    autoMapFieldAnnotation: AutoMapField?,
    fromType: KType,
    toType: KType,
  ): AutoMapBinding<AutoMapDynConverter> {
    if (
      autoMapFieldAnnotation != null &&
      autoMapFieldAnnotation.typeConverter != NotSpecifiedAutoMapTypeConverter::class
    ) {
      val converterClass = autoMapFieldAnnotation.typeConverter
      val contract = AutoMapTypeConverterIntrospector.introspectContract(converterClass)
      AutoMapTypeConverterIntrospector.requireCompatibility(
        typeConverterClass = converterClass,
        fromType = fromType,
        toType = toType,
        contract = contract,
      )

      @Suppress("UNCHECKED_CAST")
      val typedConverterClass = converterClass as KClass<AutoMapTypeConverter<Any?, Any?>>
      val converterReference =
        blueprintComponentReferences().reference(
          componentClass = typedConverterClass,
          allowNoArgConstruction = true,
          missingMessage = { "Cannot resolve converter $converterClass" },
        )
      return AutoMapBinding.fixed(
        AutoMapDynConverter { _, _, _, _, input -> converterReference.get().convert(input) }
      )
    }

    return AutoMapConverterChainBuilder.buildBlueprint(
      fromType = fromType,
      toType = toType,
      defaultConverters = defaultConvertersBlueprint,
      componentReferences = blueprintComponentReferences(),
      mapperSpec =
        autoMapFieldAnnotation
          ?.mapper
          ?.takeUnless { mapper -> mapper == Object::class },
    )
  }

  private fun buildInputCreateInfoBlueprint(
    objectKClass: KClass<*>,
    inputKClass: KClass<*>,
    mapperKClass: KClass<*>,
    annotation: AutoMapObjectFromInput,
    inputGetterFields: List<KotlinBeanField>,
  ): AutoMapBinding<InputCreateInfo> {
    val entityCompanionObjectClass =
      objectKClass.companionObject
        ?: error("Entity class $objectKClass should have Companion object for construct")
    val constructMethod =
      entityCompanionObjectClass.declaredFunctions
        .find { it.name == annotation.constructMethod }
        .unwrapElseError {
          "Cannot find method ${annotation.constructMethod} inside object cass $entityCompanionObjectClass"
        }
    val parametersByName = constructMethod.valueParameters.associateBy { it.name }

    val createFieldBindings =
      mapperKClass
        .getBeanGettersFields()
        .filter { annotation.idField != it.name }
        .map { mapperGetterField ->
          val inputGetterField =
            inputGetterFields.find { it.name == mapperGetterField.name }
              .unwrapElseError {
                "Cannot find getter field for ${mapperGetterField.name}. " +
                  "\nMapper: $mapperKClass\nInput $inputKClass\nOutput: $objectKClass"
              }
          val fieldAnnotation =
            mapperGetterField.function.findAnnotations(AutoMapField::class).firstOrNull()
          val namesBinding = resolveFieldNamesBlueprint(fieldAnnotation, inputGetterField.name)

          fun candidate(parameter: KParameter): AutoMapBinding<InputCreateFieldInfo> {
            val parameterName = checkNotNull(parameter.name)
            return getValueConverterBlueprint(
              autoMapFieldAnnotation = fieldAnnotation,
              fromType = inputGetterField.function.returnType,
              toType = parameter.type,
            ).map { converter ->
              InputCreateFieldInfo(parameterName, inputGetterField, converter)
            }
          }

          if (namesBinding.isFixed) {
            val names = namesBinding.fixedValue()
            val parameter = parametersByName[names.constructParameterName]
              .unwrapElseError {
                "Cannot find parameter \"${names.constructParameterName}\" in constructor of " +
                  "$objectKClass (mapper $mapperKClass, input $inputKClass)"
              }
            candidate(parameter)
          } else {
            val candidates =
              constructMethod.valueParameters.associate { parameter ->
                checkNotNull(parameter.name) to
                  prepareBlueprintCandidate { candidate(parameter) }
              }
            AutoMapBinding.dynamic(
              namesBinding.componentClasses +
                candidates.values.flatMapTo(linkedSetOf()) { it.componentClasses }
            ) { resolver ->
              val names = namesBinding.bind(resolver)
              candidates[names.constructParameterName]
                .unwrapElseError {
                  "Cannot find parameter \"${names.constructParameterName}\" in constructor of " +
                    "$objectKClass (mapper $mapperKClass, input $inputKClass)"
                }
                .bind(resolver)
            }
          }
        }

    return combineBindings(createFieldBindings) { createFields ->
      constructMethod.valueParameters
        .filter { parameter -> !parameter.isOptional }
        .forEach { parameter ->
          createFields.find { it.constructParameterName == parameter.name }
            .unwrapElseError {
              "Cannot find required field \"$parameter\" in \n$inputKClass for construct " +
                "\n$objectKClass (mapper \n$mapperKClass)"
            }
        }

      InputCreateInfo(
        constructorObject = entityCompanionObjectClass.objectInstance,
        constructMethod = constructMethod,
        createFields = createFields,
        createFieldsByName = createFields.associateBy(InputCreateFieldInfo::constructParameterName),
      )
    }
  }

  private fun <T> deferredBlueprintFailure(failure: Throwable): AutoMapBinding<T> {
    val message = failure.message ?: "Cannot bind mapper blueprint"
    return AutoMapBinding.dynamic { throw IllegalArgumentException(message) }
  }

  private inline fun <T> prepareBlueprintCandidate(
    factory: () -> AutoMapBinding<T>,
  ): AutoMapBinding<T> {
    return try {
      factory()
    } catch (failure: RuntimeException) {
      deferredBlueprintFailure(failure)
    }
  }

  private fun buildObjectByIdGetterBlueprint(
    objectGetterClass: KClass<*>,
    objectKClass: KClass<*>,
    idGetterField: KotlinBeanField?,
  ): AutoMapBinding<ObjectByIdGetter?> {
    if (objectGetterClass == Object::class) return AutoMapBinding.fixed(null)

    val contract = AutoMapObjectGetterIntrospector.introspectContract(objectGetterClass)
    idGetterField?.let { field ->
      AutoMapObjectGetterIntrospector.requireCompatibility(
        objectGetterClass = objectGetterClass,
        objectKClass = objectKClass,
        idType = field.function.returnType,
        introspectedGetter = contract,
      )
    }
    @Suppress("UNCHECKED_CAST")
    val typedGetterClass = objectGetterClass as KClass<Any>
    val getterReference =
      blueprintComponentReferences().reference(
        componentClass = typedGetterClass,
        missingMessage = {
          "Object Instance must be present for class $objectGetterClass unless it is an @ApplicationScoped CDI bean"
        },
      )
    return AutoMapBinding.fixed(
      ObjectByIdGetter { id ->
        contract.getterFunction.call(getterReference.get(), objectKClass, id)
      }
    )
  }

  private fun buildObjectFieldUpdaterCandidateBlueprint(
    objectKClass: KClass<*>,
    inputKClass: KClass<*>,
    mapperKClass: KClass<*>,
    fieldAnnotation: AutoMapField?,
    inputGetterField: KotlinBeanField,
    objectGetter: KotlinBeanField,
    objectSetter: KotlinBeanField?,
    missingSetterName: String,
  ): AutoMapBinding<ObjectFieldByInput> {
    val inputClassName = inputKClass.getReadableName()
    if (objectSetter == null) {
      if (objectGetter.function.returnType.findAnnotations(AutoMapObjectFromInput::class).isEmpty()) {
        return AutoMapBinding.dynamic {
          throw IllegalArgumentException(
            "Missing required setter method '$missingSetterName' for class '$objectKClass'. \n" +
              "The field from input class '$inputKClass' cannot be updated because it has no setter and is not nested."
          )
        }
      }
      if (isFieldUpdateValidatorSpecified(fieldAnnotation)) {
        return AutoMapBinding.dynamic {
          throw IllegalArgumentException(
            "Field update validator ${fieldAnnotation?.updateValidatorClass} cannot be used for " +
              "field '${inputGetterField.name}' in mapper $mapperKClass without a setter"
          )
        }
      }
      val mapperGetter =
        if (fieldAnnotation == null || fieldAnnotation.mapper == Object::class) {
          { inputValue: Any -> inputValue::class }
        } else {
          { _: Any -> fieldAnnotation.mapper }
        }

      return AutoMapBinding.fixed(
        ObjectFieldByInput(
          inputGetterField = inputGetterField,
          updater = ObjectFieldByInputUpdater { autoMapper,
                                                allowedCreationObjectClasses,
                                                allowedUpdateObjectClasses,
                                                mappingContext,
                                                _,
                                                obj,
                                                inputValue ->
            if (inputValue == null) {
              throw FieldCannotBeNullInputAutomapperException(inputGetterField.name, inputClassName)
            }
            val existingValue =
              objectGetter.function.call(obj)
                ?: throw CannotUpdateEntityInEmptyFieldInputAutomapperException(
                  inputGetterField.name,
                  inputClassName,
                )
            autoMapper.internalUpdateObjectByInput(
              mapperSpec = mapperGetter(inputValue),
              allowedCreationObjectClasses = allowedCreationObjectClasses,
              allowedUpdateObjectClasses = allowedUpdateObjectClasses,
              input = inputValue,
              obj = existingValue,
              parentContext = mappingContext,
            )
          },
        )
      )
    }

    val setterParameter = objectSetter.function.valueParameters[0]
    val setterType = setterParameter.type
    val converterBinding =
      getValueConverterBlueprint(
        autoMapFieldAnnotation = fieldAnnotation,
        fromType = inputGetterField.function.returnType,
        toType = setterType,
      )
    val validatorBinding =
      getFieldUpdateValidatorInfoBlueprint(
        autoMapFieldAnnotation = fieldAnnotation,
        fieldName = inputGetterField.name,
        mapperKClass = mapperKClass,
        parentKClass = objectKClass,
        currentType = objectGetter.function.returnType,
        newType = setterType,
        inputType = inputGetterField.function.returnType,
      )

    return converterBinding.zip(validatorBinding) { converter, validator ->
      ObjectFieldByInput(
        inputGetterField = inputGetterField,
        updater = ObjectFieldByInputUpdater { autoMapper,
                                              allowedCreationObjectClasses,
                                              allowedUpdateObjectClasses,
                                              mappingContext,
                                              _,
                                              obj,
                                              inputValue ->
          if (inputValue == null && !setterType.isMarkedNullable) {
            throw FieldCannotBeNullInputAutomapperException(inputGetterField.name, inputClassName)
          }
          val currentValue = objectGetter.function.call(obj)
          val entityValue =
            converter.convert(
              autoMapper,
              allowedCreationObjectClasses,
              allowedUpdateObjectClasses,
              mappingContext,
              inputValue,
            )
          validator?.validate(obj, currentValue, entityValue, inputValue)
          objectSetter.function.call(obj, entityValue)
        },
      )
    }
  }

  private fun buildObjectByInputUpdaterBlueprint(
    objectKClass: KClass<*>,
    annotation: AutoMapObjectFromInput,
    inputKClass: KClass<*>,
    mapperKClass: KClass<*>,
  ): AutoMapBinding<ObjectByInputUpdater> {
    val objectGetters = objectKClass.getBeanGettersFields().associateBy(KotlinBeanField::name)
    val objectSetters = objectKClass.getBeanSettersFields().associateBy(KotlinBeanField::name)
    val inputGetters = inputKClass.getBeanGettersFields().associateBy(KotlinBeanField::name)

    val fieldBindings =
      mapperKClass.getBeanGettersFields()
        .filter { it.name != annotation.idField }
        .map { mapperGetterField ->
          val fieldAnnotation =
            mapperGetterField.function.findAnnotations(AutoMapField::class).firstOrNull()
          val inputGetter = inputGetters[mapperGetterField.name]
            .unwrapElseError {
              "Cannot find field ${mapperGetterField.name} in $inputKClass (mapper $mapperKClass)"
            }
          val namesBinding = resolveFieldNamesBlueprint(fieldAnnotation, inputGetter.name)

          fun candidate(
            getter: KotlinBeanField,
            setter: KotlinBeanField?,
            missingSetterName: String,
          ): AutoMapBinding<ObjectFieldByInput> =
            buildObjectFieldUpdaterCandidateBlueprint(
              objectKClass,
              inputKClass,
              mapperKClass,
              fieldAnnotation,
              inputGetter,
              getter,
              setter,
              missingSetterName,
            )

          if (namesBinding.isFixed) {
            val names = namesBinding.fixedValue()
            val getter = objectGetters[names.getterName]
              ?: throw FieldCannotBeUpdatedInputAutomapperException(
                inputGetter.name,
                inputKClass.getReadableName(),
              )
            candidate(getter, objectSetters[names.setterName], names.setterName)
          } else {
            val explicitGetterName = fieldAnnotation?.getterFieldName?.takeIf(String::isNotEmpty)
            val explicitSetterName = fieldAnnotation?.setterFieldName?.takeIf(String::isNotEmpty)
            val candidateBindings = mutableMapOf<Pair<String, String?>, AutoMapBinding<ObjectFieldByInput>>()

            if (explicitGetterName != null) {
              val getter = objectGetters[explicitGetterName]
                ?: throw FieldCannotBeUpdatedInputAutomapperException(
                  inputGetter.name,
                  inputKClass.getReadableName(),
                )
              if (explicitSetterName != null) {
                val setter = objectSetters[explicitSetterName]
                candidateBindings[getter.name to setter?.name] =
                  prepareBlueprintCandidate { candidate(getter, setter, explicitSetterName) }
              } else {
                objectSetters.values.forEach { setter ->
                  candidateBindings[getter.name to setter.name] =
                    prepareBlueprintCandidate { candidate(getter, setter, setter.name) }
                }
                candidateBindings[getter.name to null] =
                  prepareBlueprintCandidate { candidate(getter, null, getter.name) }
              }
            } else {
              objectGetters.values.forEach { getter ->
                val setter =
                  if (explicitSetterName != null) objectSetters[explicitSetterName]
                  else objectSetters[getter.name]
                candidateBindings[getter.name to setter?.name] =
                  prepareBlueprintCandidate {
                    candidate(getter, setter, explicitSetterName ?: getter.name)
                  }
              }
            }

            AutoMapBinding.dynamic(
              namesBinding.componentClasses +
                candidateBindings.values.flatMapTo(linkedSetOf()) { it.componentClasses }
            ) { resolver ->
              val names = namesBinding.bind(resolver)
              val getter = objectGetters[names.getterName]
                ?: throw FieldCannotBeUpdatedInputAutomapperException(
                  inputGetter.name,
                  inputKClass.getReadableName(),
                )
              val setter = objectSetters[names.setterName]
              candidateBindings[getter.name to setter?.name]
                .unwrapElseError {
                  "Cannot bind update metadata for field ${inputGetter.name} in mapper $mapperKClass"
                }
                .bind(resolver)
            }
          }
        }

    return combineBindings(fieldBindings) { fields ->
      ObjectByInputUpdater { autoMapper,
                             allowedCreationObjectClasses,
                             allowedUpdateObjectClasses,
                             mappingContext,
                             obj,
                             input ->
        fields.forEach { field ->
          val inputValue = field.inputGetterField.function.call(input)
          field.updater.updateField(
            autoMapper,
            allowedCreationObjectClasses,
            allowedUpdateObjectClasses,
            mappingContext,
            field.inputGetterField,
            obj,
            inputValue,
          )
        }
      }
    }
  }

  /** Builds reflection-free runtime binders for mapper specs that depend on CDI. */
  internal fun buildBlueprint(mapperAutomapClasses: List<Class<*>>): AutoMapperBlueprint {
    check(prevalidatedLocatorKinds != null) {
      "Runtime mapper blueprints require build-time validated existing-entity locator contracts"
    }

    val preparedMappers =
      mapperAutomapClasses.associateWith { mapperClass ->
        val componentReferences = AutoMapComponentReferences()
        check(activeBlueprintComponentReferences == null) {
          "Mapper blueprints must be built sequentially"
        }
        activeBlueprintComponentReferences = componentReferences
        try {
          val mapperKClass = mapperClass.kotlin
          val direction = getMappingDirectionFromMapperSpec(mapperKClass)
          val objectKClass = direction.objectKClass
          val inputGetterFields = direction.inputKClass.getBeanGettersFields()
          val annotation =
            mapperKClass.findAnnotations(AutoMapObjectFromInput::class).firstOrNull()
              .unwrapElseError {
                "Mapper class $mapperKClass must be annotated with @${AutoMapObjectFromInput::class}"
              }
          val idGetterField =
            mapperKClass.getBeanGettersFields()
              .find { it.name == annotation.idField }
              ?.let { mapperField ->
                inputGetterFields.find { it.name == mapperField.name }
                  .unwrapElseError { "Cannot find field ${mapperField.name} in ${direction.inputKClass}" }
              }

        val createInfoBinding: AutoMapBinding<InputCreateInfo?> =
          if (annotation.allowCreate) {
            buildInputCreateInfoBlueprint(
              objectKClass,
              direction.inputKClass,
              mapperKClass,
              annotation,
              inputGetterFields,
            ).map { it }
          } else {
            AutoMapBinding.fixed(null)
          }
        val objectGetterBinding =
          buildObjectByIdGetterBlueprint(annotation.objectGetterClass, objectKClass, idGetterField)
        val updaterBinding: AutoMapBinding<ObjectByInputUpdater?> =
          if (annotation.allowUpdate) {
            buildObjectByInputUpdaterBlueprint(
              objectKClass,
              annotation,
              direction.inputKClass,
              mapperKClass,
            ).map { it }
          } else {
            AutoMapBinding.fixed(null)
          }
        val locatorBindings =
          annotation.existingEntityLookupClasses.map { locatorClass ->
            AutoMapExistingEntityLocatorIntrospector.blueprint(
              locatorClass = locatorClass,
              inputKClass = direction.inputKClass,
              targetKClass = objectKClass,
              prevalidatedKind = prevalidatedLocatorKinds[locatorClass],
              componentReferences = blueprintComponentReferences(),
            ).binding()
          }
        val locatorsBinding = combineBindings(locatorBindings) { it }

          val inputClassInfoBinding =
            createInfoBinding
              .zip(objectGetterBinding) { createInfo, objectGetter -> createInfo to objectGetter }
              .zip(updaterBinding) { (createInfo, objectGetter), updater ->
                Triple(createInfo, objectGetter, updater)
              }
              .zip(locatorsBinding) { (createInfo, objectGetter, updater), locators ->
                InputClassInfo(
                  inputKClass = direction.inputKClass,
                  objectKClass = objectKClass,
                  objectByInputUpdater = updater,
                  autoMapObjectFromInputAnnotation = annotation,
                  idGetterField = idGetterField,
                  inputCreateInfo = createInfo,
                  objectByIdGetter = objectGetter,
                  existingEntityLocators = locators,
                  batchExistingEntityLocators = locators.filterIsInstance<BatchExistingEntityLocatorInfo>(),
                )
              }
          AutoMapPreparedMapperBlueprint(inputClassInfoBinding, componentReferences)
        } finally {
          activeBlueprintComponentReferences = null
        }
      }

    return AutoMapperBlueprint(preparedMappers)
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
                  objectGetterClass = autoMapEntityFromInputAnnotation.objectGetterClass,
                  componentResolver = componentResolver,
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

        val existingEntityLocators =
          autoMapObjectFromInputAnnotation.existingEntityLookupClasses.map { lookupClass ->
            AutoMapExistingEntityLocatorIntrospector.introspect(
              locatorClass = lookupClass,
              inputKClass = mappingDirection.inputKClass,
              targetKClass = objectKClass,
              componentResolver = componentResolver,
              prevalidatedKind = prevalidatedLocatorKinds?.get(lookupClass),
            )
          }

        mapperAutomapClass to InputClassInfo(
          objectByInputUpdater = objectByInputUpdater,
          autoMapObjectFromInputAnnotation = autoMapObjectFromInputAnnotation,
          idGetterField = idGetterField,
          inputCreateInfo = inputCreateInfo,
          objectByIdGetter = objectByIdGetter,
          existingEntityLocators = existingEntityLocators,
          batchExistingEntityLocators = existingEntityLocators.filterIsInstance<BatchExistingEntityLocatorInfo>(),
          objectKClass = objectKClass,
          inputKClass = mappingDirection.inputKClass,
        )
      }

    if (prevalidatedLocatorKinds == null) {
      validateExistingEntityLocatorParentTypes(inputClassesInfoByMapperSpecClass)
    }

    return AutoMapper(
      inputClassInfoProvidersByMapperSpecClass =
        inputClassesInfoByMapperSpecClass.mapValues { (_, info) ->
          fixedInputClassInfoProvider(info)
        }
    )
  }
}
