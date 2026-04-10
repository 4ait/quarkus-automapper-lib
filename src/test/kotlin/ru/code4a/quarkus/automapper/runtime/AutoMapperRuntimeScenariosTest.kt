package ru.code4a.quarkus.automapper.runtime

import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.fieldnamingstrategies.RemoveLastIdAndIdsAutoMapFieldNamingStrategy
import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.services.AutoMapMapperBuilder
import ru.code4a.quarkus.automapper.services.AutoMapper
import ru.code4a.quarkus.automapper.services.createOrUpdateObjectByInput
import ru.code4a.quarkus.automapper.services.updateObjectByInput
import kotlin.reflect.KClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoMapperRuntimeScenariosTest {

  @BeforeTest
  fun resetState() {
    RuntimeMappingState.reset()
  }

  @Test
  fun `should create entity from matching fields`() {
    val autoMapper = buildAutoMapper(RuntimeBasicCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeBasicCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeBasicCreateInput(name = "Alice", age = 21),
      )

    assertEquals("Alice", created.name)
    assertEquals(21, created.age)
  }

  @Test
  fun `should create entity via reified create overload`() {
    val autoMapper = buildAutoMapper(RuntimeBasicCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput<RuntimePersonEntity, RuntimeBasicCreateInput>(
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeBasicCreateInput(name = "Bob", age = 32),
      )

    assertEquals("Bob", created.name)
    assertEquals(32, created.age)
  }

  @Test
  fun `should create entity using external mapper spec`() {
    val autoMapper = buildAutoMapper(RuntimeExternalPersonCreateMapperSpec::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeExternalPersonCreateMapperSpec::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeExternalPersonPayload(name = "Charlie", age = 27),
      )

    assertEquals("Charlie", created.name)
    assertEquals(27, created.age)
  }

  @Test
  fun `should create entity with fieldName remap`() {
    val autoMapper = buildAutoMapper(RuntimeFieldNameCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeFieldNameCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeFieldNameCreateInput(fullName = "Delta"),
      )

    assertEquals("Delta", created.name)
  }

  @Test
  fun `should create entity with constructParameterName remap`() {
    val autoMapper = buildAutoMapper(RuntimeConstructParameterCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeConstructParameterCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeConstructParameterCreateInput(displayName = "Echo"),
      )

    assertEquals("Echo", created.name)
  }

  @Test
  fun `should create entity with naming strategy remap`() {
    val autoMapper = buildAutoMapper(RuntimeNamingStrategyCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeNamingStrategyCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeNamingStrategyCreateInput(name = "Foxtrot", preferredLabelId = "Primary"),
      )

    assertEquals("Primary", created.getPreferredLabel())
  }

  @Test
  fun `should create entity with custom type converter`() {
    val autoMapper = buildAutoMapper(RuntimeConvertedCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeConvertedCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeConvertedCreateInput(name = "Golf", ageText = "44"),
      )

    assertEquals(44, created.age)
  }

  @Test
  fun `should create entity with inherited type converter`() {
    val autoMapper = buildAutoMapper(RuntimeInheritedConvertedCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeInheritedConvertedCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeInheritedConvertedCreateInput(name = "Golf+", ageText = "45"),
      )

    assertEquals(45, created.age)
  }

  @Test
  fun `should create entity with nested object`() {
    val autoMapper = buildAutoMapper(RuntimeNestedCreateInput::class, RuntimeAddressInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeNestedCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class, RuntimeAddressEntity::class),
        input =
          RuntimeNestedCreateInput(
            name = "Hotel",
            address = RuntimeAddressInput(id = null, city = "Vladivostok", street = "Main"),
          ),
      )

    assertEquals("Vladivostok", created.address?.city)
    assertEquals("Main", created.address?.street)
  }

  @Test
  fun `should create entity with null nested object`() {
    val autoMapper = buildAutoMapper(RuntimeNestedCreateInput::class, RuntimeAddressInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeNestedCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class, RuntimeAddressEntity::class),
        input = RuntimeNestedCreateInput(name = "India", address = null),
      )

    assertNull(created.address)
  }

  @Test
  fun `should create entity with nested children collection`() {
    val autoMapper = buildAutoMapper(RuntimeChildrenCreateInput::class, RuntimeChildInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeChildrenCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class, RuntimeChildEntity::class),
        input =
          RuntimeChildrenCreateInput(
            name = "Juliet",
            children =
              listOf(
                RuntimeChildInput(id = null, ownerId = "p-1", label = "child-1"),
                RuntimeChildInput(id = null, ownerId = "p-1", label = "child-2"),
              ),
          ),
      )

    assertEquals(2, created.children.size)
    assertEquals(setOf("child-1", "child-2"), created.children.map { it.label }.toSet())
  }

  @Test
  fun `should create entity from list to set collection mapping`() {
    val autoMapper = buildAutoMapper(RuntimeListToSetCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeListToSetCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeListToSetCreateInput(name = "Kilo", tags = listOf("a", "a", "b")),
      )

    assertEquals(setOf("a", "b"), created.tags)
  }

  @Test
  fun `should create entity from scalar to list collection mapping`() {
    val autoMapper = buildAutoMapper(RuntimeScalarAliasesCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeScalarAliasesCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeScalarAliasesCreateInput(name = "Lima", aliases = "solo"),
      )

    assertEquals(listOf("solo"), created.aliases)
  }

  @Test
  fun `should create entity from scalar to set collection mapping`() {
    val autoMapper = buildAutoMapper(RuntimeScalarTagsCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeScalarTagsCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeScalarTagsCreateInput(name = "Mike", tags = "solo"),
      )

    assertEquals(setOf("solo"), created.tags)
  }

  @Test
  fun `should create entity with nullable field set to null`() {
    val autoMapper = buildAutoMapper(RuntimeNullableNicknameCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeNullableNicknameCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeNullableNicknameCreateInput(name = "November", nickname = null),
      )

    assertNull(created.nickname)
  }

  @Test
  fun `should create entity using default construct parameter values for omitted fields`() {
    val autoMapper = buildAutoMapper(RuntimeNameOnlyCreateInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeNameOnlyCreateInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeNameOnlyCreateInput(name = "Oscar"),
      )

    assertEquals(0, created.age)
    assertEquals(emptySet(), created.tags)
    assertEquals(emptyList<String>(), created.aliases)
  }

  @Test
  fun `should create new entity when createOrUpdate receives null id`() {
    val autoMapper = buildAutoMapper(RuntimeCreateOrUpdatePersonInput::class)

    val created =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeCreateOrUpdatePersonInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeCreateOrUpdatePersonInput(id = null, name = "Papa", age = 18),
      )

    assertEquals("Papa", created.name)
    assertEquals(18, created.age)
  }

  @Test
  fun `should reject create when target class is not allowed`() {
    val autoMapper = buildAutoMapper(RuntimeBasicCreateInput::class)

    val exception =
      assertFailsWith<IllegalStateException> {
        autoMapper.createOrUpdateObjectByInput(
          mapperSpec = RuntimeBasicCreateInput::class,
          allowedCreationObjectClasses = emptySet(),
          input = RuntimeBasicCreateInput(name = "Quebec", age = 1),
        )
      }

    assertTrue(exception.message?.contains("not allowed") == true)
  }

  @Test
  fun `should reject create when non null construct field receives null`() {
    val exception =
      assertFailsWith<IllegalStateException> {
        buildAutoMapper(RuntimeNullNameCreateInput::class)
      }

    assertTrue(exception.message?.contains("not compilable") == true)
  }

  @Test
  fun `should update entity from matching fields`() {
    val autoMapper = buildAutoMapper(RuntimeBasicUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "Romeo", age = 10)

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeBasicUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeBasicUpdateInput(id = entity.id, name = "Romeo+", age = 11),
      obj = entity,
    )

    assertEquals("Romeo+", entity.name)
    assertEquals(11, entity.age)
  }

  @Test
  fun `should update entity with fieldName remap`() {
    val autoMapper = buildAutoMapper(RuntimeFieldNameUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "Sierra")

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeFieldNameUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeFieldNameUpdateInput(id = entity.id, fullName = "Sierra+"),
      obj = entity,
    )

    assertEquals("Sierra+", entity.name)
  }

  @Test
  fun `should update entity with getter and setter field names`() {
    val autoMapper = buildAutoMapper(RuntimePreferredLabelUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "Tango", preferredLabel = "old")

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimePreferredLabelUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimePreferredLabelUpdateInput(id = entity.id, label = "new"),
      obj = entity,
    )

    assertEquals("new", entity.getPreferredLabel())
  }

  @Test
  fun `should update entity with custom type converter`() {
    val autoMapper = buildAutoMapper(RuntimeConvertedUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "Uniform", age = 20)

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeConvertedUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeConvertedUpdateInput(id = entity.id, ageText = "55"),
      obj = entity,
    )

    assertEquals(55, entity.age)
  }

  @Test
  fun `should update entity with nested object by id`() {
    val autoMapper = buildAutoMapper(RuntimeAddressUpdateInput::class, RuntimeAddressInput::class)
    val address = RuntimeAddressEntity.create(city = "Old", street = "Street")
    val entity = RuntimePersonEntity.create(name = "Victor", address = null)
    RuntimeMappingState.addresses[address.id] = address

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeAddressUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class, RuntimeAddressEntity::class),
      input =
        RuntimeAddressUpdateInput(
          id = entity.id,
          address = RuntimeAddressInput(id = address.id, city = "New", street = "Line"),
        ),
      obj = entity,
    )

    assertSame(address, entity.address)
    assertEquals("New", entity.address?.city)
    assertEquals("Line", entity.address?.street)
  }

  @Test
  fun `should update entity with nested object by inherited getter`() {
    val autoMapper = buildAutoMapper(RuntimeInheritedAddressUpdateInput::class, RuntimeInheritedAddressInput::class)
    val address = RuntimeAddressEntity.create(city = "North", street = "Road")
    val entity = RuntimePersonEntity.create(name = "Victor+", address = null)

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeInheritedAddressUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class, RuntimeAddressEntity::class),
      input =
        RuntimeInheritedAddressUpdateInput(
          id = entity.id,
          address = RuntimeInheritedAddressInput(id = address.id, city = "South", street = "Lane"),
        ),
      obj = entity,
    )

    assertSame(address, entity.address)
    assertEquals("South", entity.address?.city)
    assertEquals("Lane", entity.address?.street)
    assertEquals(listOf(address.id), RuntimeMappingState.requestedAddressIds)
  }

  @Test
  fun `should update entity with null nested object`() {
    val autoMapper = buildAutoMapper(RuntimeAddressUpdateInput::class, RuntimeAddressInput::class)
    val address = RuntimeAddressEntity.create(city = "Old", street = "Street")
    val entity = RuntimePersonEntity.create(name = "Whiskey", address = address)

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeAddressUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class, RuntimeAddressEntity::class),
      input = RuntimeAddressUpdateInput(id = entity.id, address = null),
      obj = entity,
    )

    assertNull(entity.address)
  }

  @Test
  fun `should update entity with nested children collection`() {
    val autoMapper = buildAutoMapper(RuntimeChildrenUpdateInput::class, RuntimeChildInput::class)
    val currentChild = RuntimeChildEntity.create(ownerId = "owner", label = "old")
    val fetchedChild = RuntimeChildEntity.create(ownerId = "owner", label = "before")
    val entity = RuntimePersonEntity.create(name = "Xray", children = setOf(currentChild))
    RuntimeMappingState.children[fetchedChild.id] = fetchedChild

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeChildrenUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class, RuntimeChildEntity::class),
      input =
        RuntimeChildrenUpdateInput(
          id = entity.id,
          children = listOf(RuntimeChildInput(id = fetchedChild.id, ownerId = "owner", label = "after")),
        ),
      obj = entity,
    )

    assertEquals(setOf(fetchedChild), entity.children)
    assertEquals("after", entity.children.single().label)
  }

  @Test
  fun `should update entity with nested child creation`() {
    val autoMapper = buildAutoMapper(RuntimeChildrenUpdateInput::class, RuntimeChildInput::class)
    val entity = RuntimePersonEntity.create(name = "Yankee")

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeChildrenUpdateInput::class,
      allowedCreationObjectClasses = setOf(RuntimeChildEntity::class),
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class, RuntimeChildEntity::class),
      input =
        RuntimeChildrenUpdateInput(
          id = entity.id,
          children = listOf(RuntimeChildInput(id = null, ownerId = "owner-new", label = "created")),
        ),
      obj = entity,
    )

    assertEquals(1, entity.children.size)
    assertEquals("created", entity.children.single().label)
  }

  @Test
  fun `should update entity with nullable field set to null`() {
    val autoMapper = buildAutoMapper(RuntimeNullableNicknameUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "Zulu", nickname = "nick")

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeNullableNicknameUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeNullableNicknameUpdateInput(id = entity.id, nickname = null),
      obj = entity,
    )

    assertNull(entity.nickname)
  }

  @Test
  fun `should reject update when non null field receives null`() {
    val exception =
      assertFailsWith<IllegalStateException> {
        buildAutoMapper(RuntimeNullNameUpdateInput::class)
      }

    assertTrue(exception.message?.contains("not compilable") == true)
  }

  @Test
  fun `should reject update when parent class is not allowed`() {
    val autoMapper = buildAutoMapper(RuntimeBasicUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "Baker")

    val exception =
      assertFailsWith<IllegalStateException> {
        autoMapper.updateObjectByInput(
          mapperSpec = RuntimeBasicUpdateInput::class,
          allowedUpdateObjectClasses = emptySet(),
          input = RuntimeBasicUpdateInput(id = entity.id, name = "Baker+", age = 3),
          obj = entity,
        )
      }

    assertTrue(exception.message?.contains("Access to update") == true)
  }

  @Test
  fun `should update existing entity when createOrUpdate receives id`() {
    val autoMapper = buildAutoMapper(RuntimeCreateOrUpdatePersonInput::class)
    val entity = RuntimePersonEntity.create(name = "Charlie", age = 7)

    val result =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeCreateOrUpdatePersonInput::class,
        allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
        allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeCreateOrUpdatePersonInput(id = entity.id, name = "Charlie+", age = 8),
      )

    assertSame(entity, result)
    assertEquals("Charlie+", entity.name)
    assertEquals(8, entity.age)
  }

  @Test
  fun `should fail createOrUpdate when object by id is missing`() {
    val autoMapper = buildAutoMapper(RuntimeCreateOrUpdatePersonInput::class)

    val exception =
      assertFailsWith<IllegalStateException> {
        autoMapper.createOrUpdateObjectByInput(
          mapperSpec = RuntimeCreateOrUpdatePersonInput::class,
          allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
          allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
          input = RuntimeCreateOrUpdatePersonInput(id = "missing", name = "Nope", age = 1),
        )
      }

    assertTrue(exception.message?.contains("not found") == true)
  }

  @Test
  fun `should not mutate entity when allowUpdate is false`() {
    val autoMapper = buildAutoMapper(RuntimeNoUpdatePersonInput::class)
    val entity = RuntimePersonEntity.create(name = "Delta", age = 9)

    val result =
      autoMapper.createOrUpdateObjectByInput(
        mapperSpec = RuntimeNoUpdatePersonInput::class,
        allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
        input = RuntimeNoUpdatePersonInput(id = entity.id, name = "Delta+", age = 10),
      )

    assertSame(entity, result)
    assertEquals("Delta", entity.name)
    assertEquals(9, entity.age)
  }

  @Test
  fun `should record parent getter id lookup during update`() {
    val autoMapper = buildAutoMapper(RuntimeCreateOrUpdatePersonInput::class)
    val entity = RuntimePersonEntity.create(name = "Echo", age = 4)

    autoMapper.createOrUpdateObjectByInput(
      mapperSpec = RuntimeCreateOrUpdatePersonInput::class,
      allowedCreationObjectClasses = setOf(RuntimePersonEntity::class),
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeCreateOrUpdatePersonInput(id = entity.id, name = "Echo+", age = 5),
    )

    assertEquals(listOf(entity.id), RuntimeMappingState.requestedPersonIds)
  }

  @Test
  fun `should record nested getter id lookup during update`() {
    val autoMapper = buildAutoMapper(RuntimeAddressUpdateInput::class, RuntimeAddressInput::class)
    val address = RuntimeAddressEntity.create(city = "One", street = "Two")
    val entity = RuntimePersonEntity.create(name = "Foxtrot")
    RuntimeMappingState.addresses[address.id] = address

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeAddressUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class, RuntimeAddressEntity::class),
      input = RuntimeAddressUpdateInput(id = entity.id, address = RuntimeAddressInput(id = address.id, city = "Three", street = "Four")),
      obj = entity,
    )

    assertEquals(listOf(address.id), RuntimeMappingState.requestedAddressIds)
  }

  @Test
  fun `should update entity using external mapper spec`() {
    val autoMapper = buildAutoMapper(RuntimeExternalPersonUpdateMapperSpec::class)
    val entity = RuntimePersonEntity.create(name = "Golf", age = 14)

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeExternalPersonUpdateMapperSpec::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeExternalPersonUpdatePayload(id = entity.id, name = "Golf+"),
      obj = entity,
    )

    assertEquals("Golf+", entity.name)
  }

  @Test
  fun `should update entity with combined field remap and converter`() {
    val autoMapper = buildAutoMapper(RuntimeCombinedUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "hotel")

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeCombinedUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeCombinedUpdateInput(id = entity.id, displayName = "  mixed Case  "),
      obj = entity,
    )

    assertEquals("MIXED CASE", entity.name)
  }

  @Test
  fun `should update set field from scalar input`() {
    val autoMapper = buildAutoMapper(RuntimeScalarTagsUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "india", tags = setOf("old"))

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeScalarTagsUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeScalarTagsUpdateInput(id = entity.id, tags = "single"),
      obj = entity,
    )

    assertEquals(setOf("single"), entity.tags)
  }

  @Test
  fun `should update set field from list input with deduplication`() {
    val autoMapper = buildAutoMapper(RuntimeListTagsUpdateInput::class)
    val entity = RuntimePersonEntity.create(name = "juliet", tags = setOf("old"))

    autoMapper.updateObjectByInput(
      mapperSpec = RuntimeListTagsUpdateInput::class,
      allowedUpdateObjectClasses = setOf(RuntimePersonEntity::class),
      input = RuntimeListTagsUpdateInput(id = entity.id, tags = listOf("x", "x", "y")),
      obj = entity,
    )

    assertEquals(setOf("x", "y"), entity.tags)
  }

  private fun buildAutoMapper(vararg mapperSpecClasses: KClass<*>): AutoMapper {
    return AutoMapMapperBuilder()
      .build(
        mapperSpecClasses.map { it.java }
      )
  }
}

object RuntimeMappingState {
  val people = mutableMapOf<String, RuntimePersonEntity>()
  val addresses = mutableMapOf<String, RuntimeAddressEntity>()
  val children = mutableMapOf<String, RuntimeChildEntity>()
  val requestedPersonIds = mutableListOf<String>()
  val requestedAddressIds = mutableListOf<String>()
  val requestedChildIds = mutableListOf<String>()

  private var nextPersonId: Int = 1
  private var nextAddressId: Int = 1
  private var nextChildId: Int = 1

  fun nextPersonId(): String = "person-${nextPersonId++}"
  fun nextAddressId(): String = "address-${nextAddressId++}"
  fun nextChildId(): String = "child-${nextChildId++}"

  fun reset() {
    people.clear()
    addresses.clear()
    children.clear()
    requestedPersonIds.clear()
    requestedAddressIds.clear()
    requestedChildIds.clear()
    nextPersonId = 1
    nextAddressId = 1
    nextChildId = 1
  }
}

class RuntimePersonEntity(
  val id: String,
  var name: String,
  var age: Int,
  var nickname: String?,
  var tags: Set<String>,
  var aliases: List<String>,
  var address: RuntimeAddressEntity?,
  var children: Set<RuntimeChildEntity>,
  private var preferredLabel: String,
) {
  companion object {
    fun create(
      name: String,
      age: Int = 0,
      nickname: String? = null,
      tags: Set<String> = emptySet(),
      aliases: List<String> = emptyList(),
      address: RuntimeAddressEntity? = null,
      children: Set<RuntimeChildEntity> = emptySet(),
      preferredLabel: String = "",
    ): RuntimePersonEntity {
      val entity =
        RuntimePersonEntity(
          id = RuntimeMappingState.nextPersonId(),
          name = name,
          age = age,
          nickname = nickname,
          tags = tags,
          aliases = aliases,
          address = address,
          children = children,
          preferredLabel = preferredLabel,
        )
      RuntimeMappingState.people[entity.id] = entity
      return entity
    }
  }

  fun getPreferredLabel(): String = preferredLabel

  fun setPreferredLabel(value: String) {
    preferredLabel = value
  }
}

data class RuntimeAddressEntity(
  val id: String,
  var city: String,
  var street: String,
) {
  companion object {
    fun create(city: String, street: String): RuntimeAddressEntity {
      val entity =
        RuntimeAddressEntity(
          id = RuntimeMappingState.nextAddressId(),
          city = city,
          street = street,
        )
      RuntimeMappingState.addresses[entity.id] = entity
      return entity
    }
  }
}

data class RuntimeChildEntity(
  val id: String,
  var ownerId: String,
  var label: String,
) {
  companion object {
    fun create(ownerId: String, label: String): RuntimeChildEntity {
      val entity =
        RuntimeChildEntity(
          id = RuntimeMappingState.nextChildId(),
          ownerId = ownerId,
          label = label,
        )
      RuntimeMappingState.children[entity.id] = entity
      return entity
    }
  }
}

object RuntimePersonGetter {
  fun get(entityClass: KClass<*>, id: String): Any? {
    RuntimeMappingState.requestedPersonIds += id
    return RuntimeMappingState.people[id]
  }
}

object RuntimeAddressGetter {
  fun get(entityClass: KClass<*>, id: String): Any? {
    RuntimeMappingState.requestedAddressIds += id
    return RuntimeMappingState.addresses[id]
  }
}

open class RuntimeBaseAddressGetter {
  fun get(entityClass: KClass<*>, id: String): Any? {
    RuntimeMappingState.requestedAddressIds += id
    return RuntimeMappingState.addresses[id]
  }
}

object RuntimeInheritedAddressGetter : RuntimeBaseAddressGetter()

object RuntimeChildGetter {
  fun get(entityClass: KClass<*>, id: String): Any? {
    RuntimeMappingState.requestedChildIds += id
    return RuntimeMappingState.children[id]
  }
}

open class BaseStringToIntTestConverter : AutoMapTypeConverter<String, Int> {
  override fun convert(value: String): Int = value.toInt()
}

class InheritedStringToIntTestConverter : BaseStringToIntTestConverter()

class StringToIntTestConverter : AutoMapTypeConverter<String, Int> {
  override fun convert(value: String): Int = value.toInt()
}

class TrimUppercaseStringTestConverter : AutoMapTypeConverter<String, String> {
  override fun convert(value: String): String = value.trim().uppercase()
}

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeBasicCreateInput(
  var name: String,
  var age: Int,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeFieldNameCreateInput(
  @get:AutoMapField(fieldName = "name")
  var fullName: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeConstructParameterCreateInput(
  @get:AutoMapField(constructParameterName = "name")
  var displayName: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeNamingStrategyCreateInput(
  var name: String,
  @get:AutoMapField(namingStrategy = RemoveLastIdAndIdsAutoMapFieldNamingStrategy::class)
  var preferredLabelId: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeConvertedCreateInput(
  var name: String,
  @get:AutoMapField(constructParameterName = "age", typeConverter = StringToIntTestConverter::class)
  var ageText: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeInheritedConvertedCreateInput(
  var name: String,
  @get:AutoMapField(constructParameterName = "age", typeConverter = InheritedStringToIntTestConverter::class)
  var ageText: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeNestedCreateInput(
  var name: String,
  var address: RuntimeAddressInput?,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeChildrenCreateInput(
  var name: String,
  var children: List<RuntimeChildInput>,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeListToSetCreateInput(
  var name: String,
  var tags: List<String>,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeScalarAliasesCreateInput(
  var name: String,
  var aliases: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeScalarTagsCreateInput(
  var name: String,
  var tags: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeNullableNicknameCreateInput(
  var name: String,
  var nickname: String?,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeNameOnlyCreateInput(
  var name: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(constructMethod = "create")
class RuntimeNullNameCreateInput(
  var name: String?,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimeAddressGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class RuntimeAddressInput(
  var id: String?,
  var city: String,
  var street: String,
) : AutoMapperSpecTo<RuntimeAddressEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimeInheritedAddressGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class RuntimeInheritedAddressInput(
  var id: String?,
  var city: String,
  var street: String,
) : AutoMapperSpecTo<RuntimeAddressEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimeChildGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class RuntimeChildInput(
  var id: String?,
  var ownerId: String,
  var label: String,
) : AutoMapperSpecTo<RuntimeChildEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeBasicUpdateInput(
  var id: String?,
  var name: String,
  var age: Int,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeFieldNameUpdateInput(
  var id: String?,
  @get:AutoMapField(fieldName = "name")
  var fullName: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimePreferredLabelUpdateInput(
  var id: String?,
  @get:AutoMapField(getterFieldName = "preferredLabel", setterFieldName = "preferredLabel")
  var label: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeConvertedUpdateInput(
  var id: String?,
  @get:AutoMapField(fieldName = "age", typeConverter = StringToIntTestConverter::class)
  var ageText: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeAddressUpdateInput(
  var id: String?,
  var address: RuntimeAddressInput?,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeInheritedAddressUpdateInput(
  var id: String?,
  var address: RuntimeInheritedAddressInput?,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeChildrenUpdateInput(
  var id: String?,
  var children: List<RuntimeChildInput>,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeNullableNicknameUpdateInput(
  var id: String?,
  var nickname: String?,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeNullNameUpdateInput(
  var id: String?,
  var name: String?,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = true,
)
class RuntimeCreateOrUpdatePersonInput(
  var id: String?,
  var name: String,
  var age: Int,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = false,
  allowCreate = false,
)
class RuntimeNoUpdatePersonInput(
  var id: String?,
  var name: String,
  var age: Int,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeCombinedUpdateInput(
  var id: String?,
  @get:AutoMapField(fieldName = "name", typeConverter = TrimUppercaseStringTestConverter::class)
  var displayName: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeScalarTagsUpdateInput(
  var id: String?,
  var tags: String,
) : AutoMapperSpecTo<RuntimePersonEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
class RuntimeListTagsUpdateInput(
  var id: String?,
  var tags: List<String>,
) : AutoMapperSpecTo<RuntimePersonEntity>

data class RuntimeExternalPersonPayload(
  val name: String,
  val age: Int,
)

@AutoMapObjectFromInput(constructMethod = "create")
abstract class RuntimeExternalPersonCreateMapperSpec : AutoMapperSpec<RuntimeExternalPersonPayload, RuntimePersonEntity> {
  abstract val name: String
  abstract val age: Int
}

data class RuntimeExternalPersonUpdatePayload(
  val id: String,
  val name: String,
)

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = RuntimePersonGetter::class,
  allowUpdate = true,
  allowCreate = false,
)
abstract class RuntimeExternalPersonUpdateMapperSpec : AutoMapperSpec<RuntimeExternalPersonUpdatePayload, RuntimePersonEntity> {
  abstract val id: String
  abstract val name: String
}
