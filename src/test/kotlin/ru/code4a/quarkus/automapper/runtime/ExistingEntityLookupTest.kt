package ru.code4a.quarkus.automapper.runtime

import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.interfaces.AutoMapBatchExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityConflictPolicy
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookupOrder
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.services.AutoMapBatchExistingEntityLookupContext
import ru.code4a.quarkus.automapper.services.AutoMapExistingEntityLookupContext
import ru.code4a.quarkus.automapper.services.AutoMapMapperBuilder
import ru.code4a.quarkus.automapper.services.AutoMapper
import kotlin.reflect.KClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExistingEntityLookupTest {

  @BeforeTest
  fun resetState() {
    LookupState.reset()
  }

  @Test
  fun `id lookup remains the first strategy by default`() {
    val contract = LookupContractEntity("contract-1")
    val existing = LookupSubjectEntity("link-501", contract.id, "subject-20", "old")
    LookupState.subjectsById[existing.id] = existing
    LookupState.subjectsByNaturalKey[contract.id to existing.subjectId] = existing

    mapper(LookupContractInput::class, LookupSubjectInput::class)
      .updateObjectByInput(
        mapperSpec = LookupContractInput::class,
        allowedCreationObjectClasses = setOf(LookupSubjectEntity::class),
        allowedUpdateObjectClasses = setOf(LookupContractEntity::class, LookupSubjectEntity::class),
        input =
          LookupContractInput(
            id = contract.id,
            subjects = listOf(LookupSubjectInput(existing.id, existing.subjectId, "new")),
          ),
        obj = contract,
      )

    assertSame(existing, contract.subjects.single())
    assertEquals("new", existing.note)
    assertEquals(listOf(existing.id), LookupState.requestedIds)
    assertEquals(0, LookupState.batchLoads)
  }

  @Test
  fun `batch natural key lookup reuses nested entities and receives parent target`() {
    val contract = LookupContractEntity("contract-10")
    val first = LookupSubjectEntity("link-501", contract.id, "subject-20", "old-1")
    val second = LookupSubjectEntity("link-502", contract.id, "subject-30", "old-2")
    LookupState.subjectsByNaturalKey[contract.id to first.subjectId] = first
    LookupState.subjectsByNaturalKey[contract.id to second.subjectId] = second

    mapper(LookupContractInput::class, LookupSubjectInput::class)
      .updateObjectByInput(
        mapperSpec = LookupContractInput::class,
        allowedCreationObjectClasses = setOf(LookupSubjectEntity::class),
        allowedUpdateObjectClasses = setOf(LookupContractEntity::class, LookupSubjectEntity::class),
        input =
          LookupContractInput(
            id = contract.id,
            subjects =
              listOf(
                LookupSubjectInput(null, first.subjectId, "new-1"),
                LookupSubjectInput(null, second.subjectId, "new-2"),
              ),
          ),
        obj = contract,
      )

    assertSame(first, contract.subjects[0])
    assertSame(second, contract.subjects[1])
    assertEquals(listOf("new-1", "new-2"), contract.subjects.map { it.note })
    assertEquals(1, LookupState.batchLoads)
    assertEquals(contract, LookupState.batchParent)
    assertEquals(0, LookupState.createdSubjects)
  }

  @Test
  fun `external collection mapper uses its existing entity lookup`() {
    val contract = LookupContractEntity("contract-external")
    val existing = LookupSubjectEntity("link-external", contract.id, "subject-external", "old")
    LookupState.subjectsByNaturalKey[contract.id to existing.subjectId] = existing

    mapper(
      ExternalLookupContractInput::class,
      ExternalLookupSubjectInput::class,
      ExternalLookupSubjectMapper::class,
    ).updateObjectByInput(
      mapperSpec = ExternalLookupContractInput::class,
      allowedCreationObjectClasses = setOf(LookupSubjectEntity::class),
      allowedUpdateObjectClasses = setOf(LookupContractEntity::class, LookupSubjectEntity::class),
      input =
        ExternalLookupContractInput(
          id = contract.id,
          subjects = listOf(ExternalLookupSubjectInput(null, existing.subjectId, "new")),
        ),
      obj = contract,
    )

    assertSame(existing, contract.subjects.single())
    assertEquals("new", existing.note)
    assertEquals(1, LookupState.batchLoads)
    assertEquals(0, LookupState.createdSubjects)
  }

  @Test
  fun `batch lookup loads multiple unique keys once and caches a duplicate key`() {
    val contract = LookupContractEntity("contract-batch")
    val first = LookupSubjectEntity("link-a", contract.id, "a", "old")
    val second = LookupSubjectEntity("link-b", contract.id, "b", "old")
    LookupState.subjectsByNaturalKey[contract.id to "a"] = first
    LookupState.subjectsByNaturalKey[contract.id to "b"] = second

    mapper(LookupContractInput::class, LookupSubjectInput::class)
      .updateObjectByInput(
        mapperSpec = LookupContractInput::class,
        allowedCreationObjectClasses = setOf(LookupSubjectEntity::class),
        allowedUpdateObjectClasses = setOf(LookupContractEntity::class, LookupSubjectEntity::class),
        input =
          LookupContractInput(
            contract.id,
            listOf(
              LookupSubjectInput(null, "a", "first update"),
              LookupSubjectInput(null, "b", "second update"),
              LookupSubjectInput(null, "a", "last update"),
            ),
          ),
        obj = contract,
      )

    assertEquals(1, LookupState.batchLoads)
    assertEquals(setOf("a", "b"), LookupState.lastBatchKeys)
    assertSame(first, contract.subjects[0])
    assertSame(first, contract.subjects[2])
    assertEquals("last update", first.note)
  }

  @Test
  fun `factory creates a new entity only when all strategies miss`() {
    val contract = LookupContractEntity("contract-new")

    mapper(LookupContractInput::class, LookupSubjectInput::class)
      .updateObjectByInput(
        mapperSpec = LookupContractInput::class,
        allowedCreationObjectClasses = setOf(LookupSubjectEntity::class),
        allowedUpdateObjectClasses = setOf(LookupContractEntity::class, LookupSubjectEntity::class),
        input =
          LookupContractInput(
            contract.id,
            listOf(LookupSubjectInput(null, "new-subject", "new note")),
          ),
        obj = contract,
      )

    assertEquals(1, LookupState.batchLoads)
    assertEquals(1, LookupState.createdSubjects)
    assertEquals("new-subject", contract.subjects.single().subjectId)
  }

  @Test
  fun `domain parent validation failure is propagated`() {
    val contract = LookupContractEntity("contract-owner")
    val foreign = LookupSubjectEntity("foreign", "another-contract", "subject", "old")
    LookupState.subjectsByNaturalKey[contract.id to foreign.subjectId] = foreign

    val failure =
      assertFailsWith<IllegalArgumentException> {
        mapper(LookupContractInput::class, LookupSubjectInput::class)
          .updateObjectByInput(
            mapperSpec = LookupContractInput::class,
            allowedCreationObjectClasses = setOf(LookupSubjectEntity::class),
            allowedUpdateObjectClasses = setOf(LookupContractEntity::class, LookupSubjectEntity::class),
            input =
              LookupContractInput(
                contract.id,
                listOf(LookupSubjectInput(null, foreign.subjectId, "new")),
              ),
            obj = contract,
          )
      }

    assertEquals("Located subject belongs to another contract", failure.message)
  }

  @Test
  fun `custom lookup strategies run in declared order before id`() {
    val custom = OrderedLookupEntity("custom", "natural", "old")
    val byId = OrderedLookupEntity("id", "natural", "old")
    LookupState.orderedCustomEntity = custom
    LookupState.orderedByIdEntity = byId

    val result =
      mapper(OrderedLookupInput::class)
        .createOrUpdateObjectByInput(
          mapperSpec = OrderedLookupInput::class,
          allowedUpdateObjectClasses = setOf(OrderedLookupEntity::class),
          input = OrderedLookupInput(id = "id", naturalKey = "natural", value = "new"),
        )

    assertSame(custom, result)
    assertEquals(listOf("first", "second"), LookupState.lookupOrder)
    assertEquals("new", custom.value)
  }

  @Test
  fun `single lookup also works outside a collection`() {
    val existing = OrderedLookupEntity("custom", "natural", "old")
    LookupState.orderedCustomEntity = existing

    val result =
      mapper(OrderedLookupInput::class)
        .createOrUpdateObjectByInput(
          mapperSpec = OrderedLookupInput::class,
          allowedUpdateObjectClasses = setOf(OrderedLookupEntity::class),
          input = OrderedLookupInput(id = null, naturalKey = "natural", value = "new"),
        )

    assertSame(existing, result)
    assertEquals("new", existing.value)
  }

  @Test
  fun `lookup exception is propagated unchanged`() {
    val failure =
      assertFailsWith<LookupFailure> {
        mapper(FailingLookupInput::class)
          .createOrUpdateObjectByInput(
            mapperSpec = FailingLookupInput::class,
            allowedCreationObjectClasses = setOf(OrderedLookupEntity::class),
            input = FailingLookupInput("key", "value"),
          )
      }

    assertEquals("lookup failed", failure.message)
  }

  @Test
  fun `fail on conflict rejects different target instances`() {
    LookupState.conflictFirst = OrderedLookupEntity("first", "key", "old")
    LookupState.conflictSecond = OrderedLookupEntity("second", "key", "old")

    val failure =
      assertFailsWith<IllegalStateException> {
        mapper(ConflictingLookupInput::class)
          .createOrUpdateObjectByInput(
            mapperSpec = ConflictingLookupInput::class,
            allowedUpdateObjectClasses = setOf(OrderedLookupEntity::class),
            input = ConflictingLookupInput("key", "value"),
          )
      }

    assertTrue(failure.message.orEmpty().contains("returned different target instances"))
  }

  private fun mapper(vararg mapperSpecs: KClass<*>): AutoMapper {
    return AutoMapMapperBuilder().build(mapperSpecs.map { it.java })
  }
}

object LookupState {
  val subjectsById = mutableMapOf<String, LookupSubjectEntity>()
  val subjectsByNaturalKey = mutableMapOf<Pair<String, String>, LookupSubjectEntity>()
  val requestedIds = mutableListOf<String>()
  val lookupOrder = mutableListOf<String>()
  var batchLoads = 0
  var batchParent: LookupContractEntity? = null
  var lastBatchKeys: Set<String> = emptySet()
  var createdSubjects = 0
  var orderedCustomEntity: OrderedLookupEntity? = null
  var orderedByIdEntity: OrderedLookupEntity? = null
  var conflictFirst: OrderedLookupEntity? = null
  var conflictSecond: OrderedLookupEntity? = null

  fun reset() {
    subjectsById.clear()
    subjectsByNaturalKey.clear()
    requestedIds.clear()
    lookupOrder.clear()
    batchLoads = 0
    batchParent = null
    lastBatchKeys = emptySet()
    createdSubjects = 0
    orderedCustomEntity = null
    orderedByIdEntity = null
    conflictFirst = null
    conflictSecond = null
  }
}

class LookupContractEntity(
  val id: String,
  var subjects: List<LookupSubjectEntity> = emptyList(),
)

class LookupSubjectEntity(
  val id: String,
  val contractId: String,
  var subjectId: String,
  var note: String,
) {
  companion object {
    fun create(subjectId: String, note: String): LookupSubjectEntity {
      LookupState.createdSubjects++
      return LookupSubjectEntity("created-${LookupState.createdSubjects}", "", subjectId, note)
    }
  }
}

object LookupSubjectById {
  fun get(entityClass: KClass<*>, id: String): Any? {
    LookupState.requestedIds += id
    return LookupState.subjectsById[id]
  }
}

object LookupSubjectByNaturalKey :
  AutoMapBatchExistingEntityLookup<
    LookupSubjectInput,
    LookupSubjectEntity,
    String,
    LookupContractInput,
    LookupContractEntity
    > {

  override fun getLookupKey(
    input: LookupSubjectInput,
    context: AutoMapExistingEntityLookupContext<
      LookupSubjectInput,
      LookupSubjectEntity,
      LookupContractInput,
      LookupContractEntity
      >,
  ): String {
    return input.subjectId
  }

  override fun loadExisting(
    keys: Set<String>,
    inputs: List<LookupSubjectInput>,
    context: AutoMapBatchExistingEntityLookupContext<
      LookupSubjectInput,
      LookupSubjectEntity,
      LookupContractInput,
      LookupContractEntity
      >,
  ): Map<String, LookupSubjectEntity> {
    LookupState.batchLoads++
    LookupState.lastBatchKeys = keys
    val parent = context.parentTarget as LookupContractEntity
    LookupState.batchParent = parent
    return keys.mapNotNull { key ->
      LookupState.subjectsByNaturalKey[parent.id to key]?.let { key to it }
    }.toMap()
  }

  override fun validateExisting(
    target: LookupSubjectEntity,
    input: LookupSubjectInput,
    context: AutoMapExistingEntityLookupContext<
      LookupSubjectInput,
      LookupSubjectEntity,
      LookupContractInput,
      LookupContractEntity
      >,
  ) {
    val parent = context.parentTarget as LookupContractEntity
    require(target.contractId == parent.id) { "Located subject belongs to another contract" }
  }
}

@AutoMapObjectFromInput(
  idField = "id",
  objectGetterClass = LookupContractById::class,
  allowUpdate = true,
  allowCreate = false,
)
class LookupContractInput(
  var id: String?,
  var subjects: List<LookupSubjectInput>,
) : AutoMapperSpecTo<LookupContractEntity>

object LookupContractById {
  fun get(entityClass: KClass<*>, id: String): Any? = null
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = LookupSubjectById::class,
  allowUpdate = true,
  allowCreate = true,
  existingEntityLookupClasses = [LookupSubjectByNaturalKey::class],
)
class LookupSubjectInput(
  var id: String?,
  val subjectId: String,
  var note: String,
) : AutoMapperSpecTo<LookupSubjectEntity>

object ExternalLookupSubjectByNaturalKey :
  AutoMapBatchExistingEntityLookup<
    ExternalLookupSubjectInput,
    LookupSubjectEntity,
    String,
    ExternalLookupContractInput,
    LookupContractEntity
    > {

  override fun getLookupKey(
    input: ExternalLookupSubjectInput,
    context: AutoMapExistingEntityLookupContext<
      ExternalLookupSubjectInput,
      LookupSubjectEntity,
      ExternalLookupContractInput,
      LookupContractEntity
      >,
  ): String = input.subjectId

  override fun loadExisting(
    keys: Set<String>,
    inputs: List<ExternalLookupSubjectInput>,
    context: AutoMapBatchExistingEntityLookupContext<
      ExternalLookupSubjectInput,
      LookupSubjectEntity,
      ExternalLookupContractInput,
      LookupContractEntity
      >,
  ): Map<String, LookupSubjectEntity> {
    LookupState.batchLoads++
    val parent = context.parentTarget as LookupContractEntity
    return keys.mapNotNull { key ->
      LookupState.subjectsByNaturalKey[parent.id to key]?.let { key to it }
    }.toMap()
  }
}

@AutoMapObjectFromInput(
  idField = "id",
  objectGetterClass = LookupContractById::class,
  allowUpdate = true,
  allowCreate = false,
)
class ExternalLookupContractInput(
  var id: String?,
  @get:AutoMapField(mapper = ExternalLookupSubjectMapper::class)
  var subjects: List<ExternalLookupSubjectInput>,
) : AutoMapperSpecTo<LookupContractEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = LookupSubjectById::class,
  allowUpdate = true,
)
class ExternalLookupSubjectInput(
  var id: String?,
  val subjectId: String,
  var note: String,
) : AutoMapperSpecTo<LookupSubjectEntity>

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = LookupSubjectById::class,
  allowUpdate = true,
  existingEntityLookupClasses = [ExternalLookupSubjectByNaturalKey::class],
)
abstract class ExternalLookupSubjectMapper :
  AutoMapperSpec<ExternalLookupSubjectInput, LookupSubjectEntity> {
  abstract val id: String?
  abstract val subjectId: String
  abstract val note: String
}

class OrderedLookupEntity(
  val id: String,
  var naturalKey: String,
  var value: String,
) {
  companion object {
    fun create(naturalKey: String, value: String): OrderedLookupEntity {
      return OrderedLookupEntity(naturalKey, naturalKey, value)
    }
  }
}

object OrderedById {
  fun get(entityClass: KClass<*>, id: String): Any? {
    LookupState.lookupOrder += "id"
    return LookupState.orderedByIdEntity
  }
}

object OrderedFirstLookup :
  AutoMapExistingEntityLookup<OrderedLookupInput, OrderedLookupEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: OrderedLookupInput,
    context: AutoMapExistingEntityLookupContext<OrderedLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): String {
    return input.naturalKey
  }

  override fun findExisting(
    input: OrderedLookupInput,
    context: AutoMapExistingEntityLookupContext<OrderedLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): OrderedLookupEntity? {
    LookupState.lookupOrder += "first"
    return null
  }
}

object OrderedSecondLookup :
  AutoMapExistingEntityLookup<OrderedLookupInput, OrderedLookupEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: OrderedLookupInput,
    context: AutoMapExistingEntityLookupContext<OrderedLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): String {
    return input.naturalKey
  }

  override fun findExisting(
    input: OrderedLookupInput,
    context: AutoMapExistingEntityLookupContext<OrderedLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): OrderedLookupEntity? {
    LookupState.lookupOrder += "second"
    return LookupState.orderedCustomEntity
  }
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = OrderedById::class,
  allowUpdate = true,
  allowCreate = true,
  existingEntityLookupClasses = [OrderedFirstLookup::class, OrderedSecondLookup::class],
  existingEntityLookupOrder = AutoMapExistingEntityLookupOrder.CUSTOM_FIRST,
)
class OrderedLookupInput(
  val id: String?,
  val naturalKey: String,
  var value: String,
) : AutoMapperSpecTo<OrderedLookupEntity>

class LookupFailure(message: String) : RuntimeException(message)

object ThrowingLookup :
  AutoMapExistingEntityLookup<FailingLookupInput, OrderedLookupEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: FailingLookupInput,
    context: AutoMapExistingEntityLookupContext<FailingLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): String = input.naturalKey

  override fun findExisting(
    input: FailingLookupInput,
    context: AutoMapExistingEntityLookupContext<FailingLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): OrderedLookupEntity? {
    throw LookupFailure("lookup failed")
  }
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  existingEntityLookupClasses = [ThrowingLookup::class],
)
class FailingLookupInput(
  val naturalKey: String,
  val value: String,
) : AutoMapperSpecTo<OrderedLookupEntity>

object ConflictFirstLookup :
  AutoMapExistingEntityLookup<ConflictingLookupInput, OrderedLookupEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: ConflictingLookupInput,
    context: AutoMapExistingEntityLookupContext<ConflictingLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): String = input.naturalKey

  override fun findExisting(
    input: ConflictingLookupInput,
    context: AutoMapExistingEntityLookupContext<ConflictingLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): OrderedLookupEntity? {
    return LookupState.conflictFirst
  }
}

object ConflictSecondLookup :
  AutoMapExistingEntityLookup<ConflictingLookupInput, OrderedLookupEntity, String, Unit, Unit> {
  override fun getLookupKey(
    input: ConflictingLookupInput,
    context: AutoMapExistingEntityLookupContext<ConflictingLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): String = input.naturalKey

  override fun findExisting(
    input: ConflictingLookupInput,
    context: AutoMapExistingEntityLookupContext<ConflictingLookupInput, OrderedLookupEntity, Unit, Unit>,
  ): OrderedLookupEntity? {
    return LookupState.conflictSecond
  }
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  allowUpdate = true,
  existingEntityLookupClasses = [ConflictFirstLookup::class, ConflictSecondLookup::class],
  existingEntityConflictPolicy = AutoMapExistingEntityConflictPolicy.FAIL_ON_CONFLICT,
)
class ConflictingLookupInput(
  val naturalKey: String,
  var value: String,
) : AutoMapperSpecTo<OrderedLookupEntity>
