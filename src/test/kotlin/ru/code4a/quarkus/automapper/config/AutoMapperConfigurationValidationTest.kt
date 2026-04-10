package ru.code4a.quarkus.automapper.config

import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldNamingStrategy
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldUpdateValidator
import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.services.AutoMapMapperBuilder
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class AutoMapperConfigurationValidationTest {

  @Test
  fun `should fail when mapper spec is missing automap annotation`() {
    assertBuildFailsWithMessage("must be annotated", MissingAutoMapAnnotationInput::class)
  }

  @Test
  fun `should fail when mapper spec does not implement automapper interface`() {
    assertBuildFailsWithMessage("must have supertype AutoMapper or AutoMapperTo", NoMapperSupertypeInput::class)
  }

  @Test
  fun `should fail when create target lacks companion object`() {
    assertBuildFailsWithMessage("should have Companion object", NoCompanionInput::class)
  }

  @Test
  fun `should fail when construct method is missing`() {
    assertBuildFailsWithMessage("Cannot find method create", MissingConstructMethodInput::class)
  }

  @Test
  fun `should fail when required construct field is not mapped`() {
    assertBuildFailsWithMessage("Cannot find required field", MissingRequiredConstructFieldInput::class)
  }

  @Test
  fun `should fail when mapper field does not exist in source input type`() {
    assertBuildFailsWithMessage("Cannot find getter field for age", SourcePayloadMapperSpec::class)
  }

  @Test
  fun `should fail when update target getter is missing`() {
    assertBuildFailsWithMessage("Field cannot be updated", NoGetterUpdateInput::class)
  }

  @Test
  fun `should fail when update target setter is missing for non nested field`() {
    assertBuildFailsWithMessage("Missing required setter method 'name'", ReadOnlyNameUpdateInput::class)
  }

  @Test
  fun `should build when object getter function is declared directly on object`() {
    assertBuildSucceeds(DirectGetterInput::class)
  }

  @Test
  fun `should build when object getter function is inherited from base class`() {
    assertBuildSucceeds(InheritedGetterInput::class)
  }

  @Test
  fun `should fail when object getter class declares multiple functions`() {
    assertBuildFailsWithMessage("must have exactly one get(entityClass, id) function", MultipleFunctionsGetterInput::class)
  }

  @Test
  fun `should fail when object getter class declares no getter function`() {
    assertBuildFailsWithMessage("must have exactly one get(entityClass, id) function", NoFunctionsGetterInput::class)
  }

  @Test
  fun `should fail when object getter function has wrong arity`() {
    assertBuildFailsWithMessage("must have 2 parameters", WrongArityGetterInput::class)
  }

  @Test
  fun `should fail when object getter entityClass type is incompatible`() {
    assertBuildFailsWithMessage("First argument of getter", WrongEntityClassTypeGetterInput::class)
  }

  @Test
  fun `should fail when object getter id type is incompatible`() {
    assertBuildFailsWithMessage("Second argument of getter", WrongIdTypeGetterInput::class)
  }

  @Test
  fun `should fail when object getter return type is incompatible`() {
    assertBuildFailsWithMessage("Return type of getter", WrongReturnTypeGetterInput::class)
  }

  @Test
  fun `should fail when object getter class is not object`() {
    assertBuildFailsWithMessage("Object Instance must be present", ClassGetterInput::class)
  }

  @Test
  fun `should fail when naming strategy is not object`() {
    assertBuildFailsWithMessage("NamingStrategy must define object", ClassNamingStrategyInput::class)
  }

  @Test
  fun `should fail when converter input type is incompatible`() {
    assertBuildFailsWithMessage("First argument of converter", IncompatibleConverterInputTypeInput::class)
  }

  @Test
  fun `should fail when converter return type is incompatible`() {
    assertBuildFailsWithMessage("Return type of converter", IncompatibleConverterReturnTypeInput::class)
  }

  @Test
  fun `should fail when update validator parent type is incompatible`() {
    assertBuildFailsWithMessage("parent type", InvalidParentUpdateValidatorInput::class)
  }

  @Test
  fun `should fail when update validator current type is incompatible`() {
    assertBuildFailsWithMessage("currentValue type", InvalidCurrentUpdateValidatorInput::class)
  }

  @Test
  fun `should fail when update validator new type is incompatible`() {
    assertBuildFailsWithMessage("newValue type", InvalidNewUpdateValidatorInput::class)
  }

  @Test
  fun `should fail when update validator input type is incompatible`() {
    assertBuildFailsWithMessage("inputValue type", InvalidInputUpdateValidatorInput::class)
  }

  @Test
  fun `should fail when update validator is not object`() {
    assertBuildFailsWithMessage("should be object instance", ClassUpdateValidatorInput::class)
  }

  private fun assertBuildFailsWithMessage(expectedMessagePart: String, vararg mapperSpecClasses: KClass<*>) {
    val exception =
      assertFails {
        AutoMapMapperBuilder().build(mapperSpecClasses.map { it.java })
      }

    assertTrue(
      exception.message?.contains(expectedMessagePart) == true,
      "Expected message to contain '$expectedMessagePart', actual='${exception.message}'"
    )
  }

  private fun assertBuildSucceeds(vararg mapperSpecClasses: KClass<*>) {
    AutoMapMapperBuilder().build(mapperSpecClasses.map { it.java })
  }
}

class ConfigValidEntity(
  val id: String,
  var name: String,
  var age: Int,
) {
  companion object {
    fun create(name: String, age: Int = 0): ConfigValidEntity {
      return ConfigValidEntity(
        id = "config-id",
        name = name,
        age = age,
      )
    }
  }
}

object ConfigGetter {
  fun get(entityClass: KClass<*>, id: String): Any? = null
}

open class ConfigBaseGetter {
  fun get(entityClass: KClass<*>, id: String): Any? = null
}

object InheritedConfigGetter : ConfigBaseGetter()

class MissingAutoMapAnnotationInput(
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class NoMapperSupertypeInput(
  var name: String,
)

class NoCompanionEntity(
  val name: String,
)

@AutoMapObjectFromInput(constructMethod = "create")
class NoCompanionInput(
  var name: String,
) : AutoMapperSpecTo<NoCompanionEntity>

class MissingConstructMethodEntity(
  val name: String,
) {
  companion object {
    fun build(name: String): MissingConstructMethodEntity {
      return MissingConstructMethodEntity(name)
    }
  }
}

@AutoMapObjectFromInput(constructMethod = "create")
class MissingConstructMethodInput(
  var name: String,
) : AutoMapperSpecTo<MissingConstructMethodEntity>

class RequiredAgeEntity(
  val name: String,
  val age: Int,
) {
  companion object {
    fun create(name: String, age: Int): RequiredAgeEntity {
      return RequiredAgeEntity(name, age)
    }
  }
}

@AutoMapObjectFromInput(constructMethod = "create")
class MissingRequiredConstructFieldInput(
  var name: String,
) : AutoMapperSpecTo<RequiredAgeEntity>

data class SourcePayload(
  val name: String,
)

@AutoMapObjectFromInput(constructMethod = "create")
abstract class SourcePayloadMapperSpec : AutoMapperSpec<SourcePayload, ConfigValidEntity> {
  abstract val name: String
  abstract val age: Int
}

class NoGetterEntity(
  val id: String,
) {
  companion object {
    fun create(): NoGetterEntity = NoGetterEntity("id")
  }
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class DirectGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = InheritedConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class InheritedGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class NoGetterUpdateInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<NoGetterEntity>

class ReadOnlyNameEntity(
  val id: String,
  val name: String,
) {
  companion object {
    fun create(name: String): ReadOnlyNameEntity = ReadOnlyNameEntity("id", name)
  }
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class ReadOnlyNameUpdateInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ReadOnlyNameEntity>

object MultipleFunctionsGetter {
  fun get(entityClass: KClass<*>, id: String): Any? = null
  fun get(entityClass: KClass<*>, id: Int): Any? = null
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = MultipleFunctionsGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class MultipleFunctionsGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

object NoFunctionsGetter

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = NoFunctionsGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class NoFunctionsGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

object WrongArityGetter {
  fun get(id: String): Any? = null
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = WrongArityGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class WrongArityGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

object WrongIdTypeGetter {
  fun get(entityClass: KClass<*>, id: Int): Any? = null
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = WrongIdTypeGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class WrongIdTypeGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

object WrongEntityClassTypeGetter {
  fun get(entityClass: String, id: String): Any? = null
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = WrongEntityClassTypeGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class WrongEntityClassTypeGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

object WrongReturnTypeGetter {
  fun get(entityClass: KClass<*>, id: String): String? = null
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = WrongReturnTypeGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class WrongReturnTypeGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

class ClassGetter {
  fun get(entityClass: KClass<*>, id: String): Any? = null
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ClassGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class ClassGetterInput(
  var id: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

class ClassNamingStrategy : AutoMapFieldNamingStrategy {
  override fun getObjectFieldName(inputName: String): String = inputName
}

@AutoMapObjectFromInput(constructMethod = "create")
class ClassNamingStrategyInput(
  @get:AutoMapField(namingStrategy = ClassNamingStrategy::class)
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

class IntToIntConverter : AutoMapTypeConverter<Int, Int> {
  override fun convert(value: Int): Int = value
}

@AutoMapObjectFromInput(constructMethod = "create")
class IncompatibleConverterInputTypeInput(
  @get:AutoMapField(constructParameterName = "age", typeConverter = IntToIntConverter::class)
  var ageText: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

class StringToLongConverter : AutoMapTypeConverter<String, Long> {
  override fun convert(value: String): Long = value.toLong()
}

@AutoMapObjectFromInput(constructMethod = "create")
class IncompatibleConverterReturnTypeInput(
  @get:AutoMapField(constructParameterName = "age", typeConverter = StringToLongConverter::class)
  var ageText: String,
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

class OtherEntity(
  val id: String,
)

object InvalidParentValidator :
  AutoMapFieldUpdateValidator<OtherEntity, String, String, String> {
  override fun validate(parent: OtherEntity, currentValue: String, newValue: String, inputValue: String, fieldName: String) = Unit
}

object InvalidCurrentValidator :
  AutoMapFieldUpdateValidator<ConfigValidEntity, Int, String, String> {
  override fun validate(parent: ConfigValidEntity, currentValue: Int, newValue: String, inputValue: String, fieldName: String) = Unit
}

object InvalidNewValidator :
  AutoMapFieldUpdateValidator<ConfigValidEntity, String, Int, String> {
  override fun validate(parent: ConfigValidEntity, currentValue: String, newValue: Int, inputValue: String, fieldName: String) = Unit
}

object InvalidInputValidator :
  AutoMapFieldUpdateValidator<ConfigValidEntity, String, String, Int> {
  override fun validate(parent: ConfigValidEntity, currentValue: String, newValue: String, inputValue: Int, fieldName: String) = Unit
}

class ClassUpdateValidator :
  AutoMapFieldUpdateValidator<ConfigValidEntity, String, String, String> {
  override fun validate(parent: ConfigValidEntity, currentValue: String, newValue: String, inputValue: String, fieldName: String) = Unit
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class InvalidParentUpdateValidatorInput(
  var id: String,
  @get:AutoMapField(updateValidatorClass = InvalidParentValidator::class)
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class InvalidCurrentUpdateValidatorInput(
  var id: String,
  @get:AutoMapField(updateValidatorClass = InvalidCurrentValidator::class)
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class InvalidNewUpdateValidatorInput(
  var id: String,
  @get:AutoMapField(updateValidatorClass = InvalidNewValidator::class)
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class InvalidInputUpdateValidatorInput(
  var id: String,
  @get:AutoMapField(updateValidatorClass = InvalidInputValidator::class)
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = ConfigGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class ClassUpdateValidatorInput(
  var id: String,
  @get:AutoMapField(updateValidatorClass = ClassUpdateValidator::class)
  var name: String,
) : AutoMapperSpecTo<ConfigValidEntity>
