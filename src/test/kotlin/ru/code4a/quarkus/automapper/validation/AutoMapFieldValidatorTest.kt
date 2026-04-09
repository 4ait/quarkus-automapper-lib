package ru.code4a.quarkus.automapper.validation

import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldUpdateValidator
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.services.AutoMapMapperBuilder
import ru.code4a.quarkus.automapper.services.AutoMapper
import kotlin.reflect.KClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame

class AutoMapFieldValidatorTest {

  @BeforeTest
  fun resetState() {
    TestParentEntityGetter.entities.clear()
    TestChildEntityGetter.entities.clear()
    RecordingChildrenValidator.contexts.clear()
    TestParentEntity.resetIds()
    TestChildEntity.resetIds()
  }

  @Test
  fun `validator should be called on update with correct context`() {
    val autoMapper =
      buildAutoMapper(
        ValidatedParentInput::class,
        TestChildInput::class,
      )

    val currentChild =
      TestChildEntity(
        id = "child-current",
        ownerId = "parent-1",
        label = "current child",
      )
    val fetchedChild =
      TestChildEntity(
        id = "child-fetched",
        ownerId = "another-parent",
        label = "before update",
      )
    val parent =
      TestParentEntity(
        id = "parent-1",
        name = "before update",
        children = setOf(currentChild),
      )

    TestParentEntityGetter.entities[parent.id] = parent
    TestChildEntityGetter.entities[fetchedChild.id] = fetchedChild

    val input =
      ValidatedParentInput(
        id = parent.id,
        name = "after update",
        children =
          listOf(
            TestChildInput(
              id = fetchedChild.id,
              ownerId = fetchedChild.ownerId,
              label = "after update",
            )
          ),
      )

    autoMapper.updateObjectByInput(
      mapperSpec = ValidatedParentInput::class,
      allowedCreationObjectClasses = emptySet(),
      allowedUpdateObjectClasses = setOf(TestParentEntity::class, TestChildEntity::class),
      input = input,
      obj = parent,
    )

    val context = RecordingChildrenValidator.contexts.single()
    assertEquals("children", context.fieldName)
    assertSame(parent, context.parent)
    assertEquals(setOf(currentChild), context.currentValue)
    assertEquals(setOf(fetchedChild), context.newValue)
    assertEquals("after update", context.newValue!!.single().label)
    assertEquals(input.children, context.inputValue)
    assertEquals(setOf(fetchedChild), parent.children)
    assertEquals("after update", parent.children.single().label)
  }

  @Test
  fun `update validator should not be called on create`() {
    val autoMapper =
      buildAutoMapper(
        ValidatedParentInput::class,
        TestChildInput::class,
      )

    val input =
      ValidatedParentInput(
        id = null,
        name = "created parent",
        children =
          listOf(
            TestChildInput(
              id = null,
              ownerId = "parent-created",
              label = "created child",
            )
          ),
      )

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = ValidatedParentInput::class,
        allowedCreationObjectClasses = setOf(TestParentEntity::class, TestChildEntity::class),
        allowedUpdateObjectClasses = setOf(TestChildEntity::class),
        input = input,
      )

    assertTrue(RecordingChildrenValidator.contexts.isEmpty())
  }

  @Test
  fun `invalid validator generics should fail during mapper build`() {
    val exception =
      assertFailsWith<IllegalArgumentException> {
        buildAutoMapper(
          InvalidValidatorParentInput::class,
          TestChildInput::class,
        )
      }

    assertEquals(true, exception.message?.contains("children"))
    assertEquals(true, exception.message?.contains("currentValue"))
  }

  @Test
  fun `validator exception should be propagated`() {
    val autoMapper =
      buildAutoMapper(
        ThrowingValidatorParentInput::class,
        TestChildInput::class,
      )

    val parent =
      TestParentEntity(
        id = "parent-throw",
        name = "before",
        children = emptySet(),
      )
    val child =
      TestChildEntity(
        id = "child-throw",
        ownerId = "parent-throw",
        label = "before",
      )

    TestParentEntityGetter.entities[parent.id] = parent
    TestChildEntityGetter.entities[child.id] = child

    val input =
      ThrowingValidatorParentInput(
        id = parent.id,
        name = "after",
        children =
          listOf(
            TestChildInput(
              id = child.id,
              ownerId = child.ownerId,
              label = "after",
            )
          ),
      )

    val exception =
      assertFailsWith<TestFieldValidationException> {
        autoMapper.updateObjectByInput(
          mapperSpec = ThrowingValidatorParentInput::class,
          allowedCreationObjectClasses = emptySet(),
          allowedUpdateObjectClasses = setOf(TestParentEntity::class, TestChildEntity::class),
          input = input,
          obj = parent,
        )
      }

    assertEquals("validator rejected field", exception.message)
  }

  private fun buildAutoMapper(vararg mapperSpecClasses: KClass<*>): AutoMapper {
    return AutoMapMapperBuilder()
      .build(
        mapperSpecClasses
          .map { it.java }
      )
  }
}

class TestFieldValidationException(message: String) : RuntimeException(message)

data class RecordedUpdateValidation(
  val parent: TestParentEntity,
  val currentValue: Set<TestChildEntity>?,
  val newValue: Set<TestChildEntity>?,
  val inputValue: List<TestChildInput>?,
  val fieldName: String,
)

class TestParentEntity(
  val id: String,
  var name: String,
  var children: Set<TestChildEntity>,
) {
  companion object {
    private var nextId: Int = 1

    fun create(name: String, children: Set<TestChildEntity>): TestParentEntity {
      val id = "created-parent-${nextId++}"
      return TestParentEntity(
        id = id,
        name = name,
        children = children,
      )
    }

    fun resetIds() {
      nextId = 1
    }
  }
}

data class TestChildEntity(
  val id: String,
  var ownerId: String,
  var label: String,
) {
  companion object {
    private var nextId: Int = 1

    fun create(ownerId: String, label: String): TestChildEntity {
      val id = "created-child-${nextId++}"
      return TestChildEntity(
        id = id,
        ownerId = ownerId,
        label = label,
      )
    }

    fun resetIds() {
      nextId = 1
    }
  }
}

object TestParentEntityGetter {
  val entities = mutableMapOf<String, TestParentEntity>()

  fun get(entityClass: KClass<*>, id: String): Any? {
    return entities[id]
  }
}

object TestChildEntityGetter {
  val entities = mutableMapOf<String, TestChildEntity>()

  fun get(entityClass: KClass<*>, id: String): Any? {
    return entities[id]
  }
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = TestChildEntityGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class TestChildInput(
  var id: String?,
  var ownerId: String,
  var label: String,
) : AutoMapperSpecTo<TestChildEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = TestParentEntityGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class ValidatedParentInput(
  var id: String?,
  var name: String,
  @get:AutoMapField(updateValidatorClass = RecordingChildrenValidator::class)
  var children: List<TestChildInput>,
) : AutoMapperSpecTo<TestParentEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = TestParentEntityGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class ThrowingValidatorParentInput(
  var id: String?,
  var name: String,
  @get:AutoMapField(updateValidatorClass = ThrowingChildrenValidator::class)
  var children: List<TestChildInput>,
) : AutoMapperSpecTo<TestParentEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = TestParentEntityGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class InvalidValidatorParentInput(
  var id: String?,
  var name: String,
  @get:AutoMapField(updateValidatorClass = InvalidChildrenValidator::class)
  var children: List<TestChildInput>,
) : AutoMapperSpecTo<TestParentEntity>

object RecordingChildrenValidator :
  AutoMapFieldUpdateValidator<TestParentEntity, Set<TestChildEntity>, Set<TestChildEntity>, List<TestChildInput>> {

  val contexts =
    mutableListOf<RecordedUpdateValidation>()

  override fun validate(
    parent: TestParentEntity,
    currentValue: Set<TestChildEntity>?,
    newValue: Set<TestChildEntity>?,
    inputValue: List<TestChildInput>?,
    fieldName: String,
  ) {
    contexts += RecordedUpdateValidation(
      parent = parent,
      currentValue = currentValue,
      newValue = newValue,
      inputValue = inputValue,
      fieldName = fieldName,
    )
  }
}

object ThrowingChildrenValidator :
  AutoMapFieldUpdateValidator<TestParentEntity, Set<TestChildEntity>, Set<TestChildEntity>, List<TestChildInput>> {

  override fun validate(
    parent: TestParentEntity,
    currentValue: Set<TestChildEntity>?,
    newValue: Set<TestChildEntity>?,
    inputValue: List<TestChildInput>?,
    fieldName: String,
  ) {
    throw TestFieldValidationException("validator rejected field")
  }
}

object InvalidChildrenValidator :
  AutoMapFieldUpdateValidator<TestParentEntity, String, Set<TestChildEntity>, List<TestChildInput>> {

  override fun validate(
    parent: TestParentEntity,
    currentValue: String?,
    newValue: Set<TestChildEntity>?,
    inputValue: List<TestChildInput>?,
    fieldName: String,
  ) {
    error("should not be called")
  }
}
