package ru.code4a.quarkus.automapper.config

import org.jboss.jandex.DotName
import org.jboss.jandex.Index
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.builds.AutoMapExistingEntityLocatorBuildTimeValidator
import ru.code4a.quarkus.automapper.interfaces.AutoMapBatchExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLocator
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.services.AutoMapBatchExistingEntityLookupContext
import ru.code4a.quarkus.automapper.services.AutoMapExistingEntityLookupContext
import ru.code4a.quarkus.automapper.services.AutoMapExistingEntityLocatorKind
import ru.code4a.quarkus.automapper.services.AutoMapMapperBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ExistingEntityLookupConfigurationValidationTest {

  @Test
  fun `valid lookup generic contract is resolved during augmentation`() {
    val contracts =
      AutoMapExistingEntityLocatorBuildTimeValidator.validate(
        buildIndex(
          ValidLocatorInput::class.java,
          ValidBuildTimeLocator::class.java,
          BuildTimeLocatorEntity::class.java,
        )
      )

    assertEquals(
      AutoMapExistingEntityLocatorKind.SINGLE,
      contracts[DotName.createSimple(ValidBuildTimeLocator::class.java)],
    )
  }

  @Test
  fun `incompatible lookup input type fails during augmentation`() {
    val failure =
      assertFails {
        AutoMapExistingEntityLocatorBuildTimeValidator.validate(
          buildIndex(
            WrongInputLocatorInput::class.java,
            WrongInputBuildTimeLocator::class.java,
            OtherBuildTimeInput::class.java,
            BuildTimeLocatorEntity::class.java,
          )
        )
      }

    assertTrue(failure.message.orEmpty().contains("input type"))
  }

  @Test
  fun `incompatible lookup parent types fail during augmentation`() {
    val failure =
      assertFails {
        AutoMapExistingEntityLocatorBuildTimeValidator.validate(
          buildIndex(
            BuildTimeParentInput::class.java,
            BuildTimeParentEntity::class.java,
            ValidBatchLocatorInput::class.java,
            ValidBatchBuildTimeLocator::class.java,
            BuildTimeLocatorEntity::class.java,
          )
        )
      }

    assertTrue(failure.message.orEmpty().contains("parent source type"))
  }

  @Test
  fun `valid lookup types are initialized while mapper metadata is built`() {
    AutoMapMapperBuilder().build(listOf(ValidLocatorInput::class.java))
    assertTrue(BuildTimeInitializationState.locatorInitialized)
  }

  @Test
  fun `incompatible lookup input type fails during metadata build`() {
    assertBuildFails("input type", WrongInputLocatorInput::class.java)
  }

  @Test
  fun `incompatible lookup target type fails during metadata build`() {
    assertBuildFails("target type", WrongTargetLocatorInput::class.java)
  }

  @Test
  fun `lookup class must be an object instance`() {
    assertBuildFails("must be an object instance", ClassLocatorInput::class.java)
  }

  @Test
  fun `batch lookup generic metadata is accepted during metadata build`() {
    AutoMapMapperBuilder().build(listOf(ValidBatchLocatorInput::class.java))
  }

  @Test
  fun `incompatible parent types fail when nested mapping is wired`() {
    val failure =
      assertFails {
        AutoMapMapperBuilder().build(
          listOf(BuildTimeParentInput::class.java, ValidBatchLocatorInput::class.java)
        )
      }
    assertTrue(failure.message.orEmpty().contains("parent source type"))
  }

  private fun assertBuildFails(messagePart: String, mapperSpec: Class<*>) {
    val failure = assertFails { AutoMapMapperBuilder().build(listOf(mapperSpec)) }
    assertTrue(
      failure.message.orEmpty().contains(messagePart),
      "Expected '${failure.message}' to contain '$messagePart'",
    )
  }

  private fun buildIndex(vararg applicationClasses: Class<*>): Index {
    return Index.of(
      listOf(
        AutoMapObjectFromInput::class.java,
        AutoMapperSpec::class.java,
        AutoMapperSpecTo::class.java,
        AutoMapExistingEntityLocator::class.java,
        AutoMapExistingEntityLookup::class.java,
        AutoMapBatchExistingEntityLookup::class.java,
        *applicationClasses,
      )
    )
  }
}

class BuildTimeLocatorEntity(
  var value: String,
) {
  companion object {
    fun create(value: String): BuildTimeLocatorEntity = BuildTimeLocatorEntity(value)
  }
}

class BuildTimeParentEntity(
  val children: List<BuildTimeLocatorEntity>,
) {
  companion object {
    fun create(children: List<BuildTimeLocatorEntity>): BuildTimeParentEntity {
      return BuildTimeParentEntity(children)
    }
  }
}

class OtherBuildTimeInput(val value: String)

object BuildTimeInitializationState {
  var locatorInitialized = false
}

object ValidBuildTimeLocator :
  AutoMapExistingEntityLookup<ValidLocatorInput, BuildTimeLocatorEntity, ValidLocatorInput, Unit, Unit> {
  init {
    BuildTimeInitializationState.locatorInitialized = true
  }

  override fun findExisting(
    input: ValidLocatorInput,
    context: AutoMapExistingEntityLookupContext<ValidLocatorInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): BuildTimeLocatorEntity? = null

  override fun getLookupKey(
    input: ValidLocatorInput,
    context: AutoMapExistingEntityLookupContext<ValidLocatorInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): ValidLocatorInput = input
}

object WrongInputBuildTimeLocator :
  AutoMapExistingEntityLookup<OtherBuildTimeInput, BuildTimeLocatorEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: OtherBuildTimeInput,
    context: AutoMapExistingEntityLookupContext<OtherBuildTimeInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): String = input.value

  override fun findExisting(
    input: OtherBuildTimeInput,
    context: AutoMapExistingEntityLookupContext<OtherBuildTimeInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): BuildTimeLocatorEntity? = null
}

object WrongTargetBuildTimeLocator :
  AutoMapExistingEntityLookup<WrongTargetLocatorInput, String, String, Unit, Unit> {
  override fun getLookupKey(
    input: WrongTargetLocatorInput,
    context: AutoMapExistingEntityLookupContext<WrongTargetLocatorInput, String, Unit, Unit>,
  ): String = input.value

  override fun findExisting(
    input: WrongTargetLocatorInput,
    context: AutoMapExistingEntityLookupContext<WrongTargetLocatorInput, String, Unit, Unit>,
  ): String? = null
}

class ClassBuildTimeLocator :
  AutoMapExistingEntityLookup<ClassLocatorInput, BuildTimeLocatorEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: ClassLocatorInput,
    context: AutoMapExistingEntityLookupContext<ClassLocatorInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): String = input.value

  override fun findExisting(
    input: ClassLocatorInput,
    context: AutoMapExistingEntityLookupContext<ClassLocatorInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): BuildTimeLocatorEntity? = null
}

object ValidBatchBuildTimeLocator :
  AutoMapBatchExistingEntityLookup<ValidBatchLocatorInput, BuildTimeLocatorEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: ValidBatchLocatorInput,
    context: AutoMapExistingEntityLookupContext<ValidBatchLocatorInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): String {
    return input.value
  }

  override fun loadExisting(
    keys: Set<String>,
    inputs: List<ValidBatchLocatorInput>,
    context: AutoMapBatchExistingEntityLookupContext<ValidBatchLocatorInput, BuildTimeLocatorEntity, Unit, Unit>,
  ): Map<String, BuildTimeLocatorEntity> = emptyMap()
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  existingEntityLookupClasses = [ValidBuildTimeLocator::class],
)
class ValidLocatorInput(
  val value: String,
) : AutoMapperSpecTo<BuildTimeLocatorEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  existingEntityLookupClasses = [WrongInputBuildTimeLocator::class],
)
class WrongInputLocatorInput(
  val value: String,
) : AutoMapperSpecTo<BuildTimeLocatorEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  existingEntityLookupClasses = [WrongTargetBuildTimeLocator::class],
)
class WrongTargetLocatorInput(
  val value: String,
) : AutoMapperSpecTo<BuildTimeLocatorEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  existingEntityLookupClasses = [ClassBuildTimeLocator::class],
)
class ClassLocatorInput(
  val value: String,
) : AutoMapperSpecTo<BuildTimeLocatorEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  existingEntityLookupClasses = [ValidBatchBuildTimeLocator::class],
)
class ValidBatchLocatorInput(
  val value: String,
) : AutoMapperSpecTo<BuildTimeLocatorEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class BuildTimeParentInput(
  val children: List<ValidBatchLocatorInput>,
) : AutoMapperSpecTo<BuildTimeParentEntity>
