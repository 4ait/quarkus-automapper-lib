package ru.code4a.quarkus.automapper.runtime

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.literal.NamedLiteral
import io.quarkus.arc.SyntheticCreationalContext
import io.quarkus.deployment.builditem.CombinedIndexBuildItem
import org.jboss.jandex.Index
import org.mockito.Mockito
import ru.code4a.quarkus.automapper.annotations.AutoMapField
import ru.code4a.quarkus.automapper.annotations.AutoMapObjectFromInput
import ru.code4a.quarkus.automapper.builds.AutoMapClassesProcessor
import ru.code4a.quarkus.automapper.interfaces.AutoMapExistingEntityLookup
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldNamingStrategy
import ru.code4a.quarkus.automapper.interfaces.AutoMapFieldUpdateValidator
import ru.code4a.quarkus.automapper.interfaces.AutoMapTypeConverter
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpec
import ru.code4a.quarkus.automapper.interfaces.AutoMapperSpecTo
import ru.code4a.quarkus.automapper.services.AutoMapExistingEntityLookupContext
import ru.code4a.quarkus.automapper.services.AutoMapMapperBuilder
import ru.code4a.quarkus.automapper.services.AutoMapInputClassInfoProvider
import ru.code4a.quarkus.automapper.services.AutoMapper
import ru.code4a.quarkus.automapper.services.AutoMapperRecorder
import ru.code4a.quarkus.automapper.services.AutoMapperStaticPart
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoMapperApplicationScopedComponentsTest {

  @Test
  fun `static blueprint records exact CDI dependencies per mapper`() {
    val blueprint =
      AutoMapMapperBuilder(
        cdiLookup = { null },
        prevalidatedLocatorKinds = emptyMap(),
        defaultConverterClassNames = emptyList(),
      ).buildBlueprint(listOf(StaticInput::class.java, DiInput::class.java))

    assertEquals(emptySet(), blueprint.componentClasses(StaticInput::class.java))
    assertEquals(
      setOf(
        DiNamingStrategy::class,
        DiTypeConverter::class,
        DiUpdateValidator::class,
        DiEntityGetter::class,
      ),
      blueprint.componentClasses(DiInput::class.java),
    )
  }

  @Test
  fun `augmentation plan keeps component injection points mapper local`() {
    val index =
      Index.of(
        AutoMapObjectFromInput::class.java,
        AutoMapField::class.java,
        AutoMapperSpec::class.java,
        AutoMapperSpecTo::class.java,
        StaticInput::class.java,
        StaticTypeConverter::class.java,
        DiInput::class.java,
        DiNamingStrategy::class.java,
        DiTypeConverter::class.java,
        DiUpdateValidator::class.java,
        DiEntityGetter::class.java,
      )
    val plan =
      AutoMapClassesProcessor().createAutoMapperInitializationPlan(
        CombinedIndexBuildItem(index, index)
      )

    assertEquals(listOf(StaticInput::class.java.name), plan.staticMapperClassNames.toList())
    assertEquals(listOf(DiInput::class.java.name), plan.runtimeMapperClassNames.toList())
    assertEquals(
      setOf(
        DiNamingStrategy::class.java.name,
        DiTypeConverter::class.java.name,
        DiUpdateValidator::class.java.name,
        DiEntityGetter::class.java.name,
      ),
      plan.applicationScopedComponentClassNamesByMapper
        .getValue(DiInput::class.java.name)
        .toSet(),
    )
  }

  @Test
  fun `object only mapper metadata is initialized in static phase`() {
    val staticPart =
      AutoMapperRecorder()
        .createStaticPart(
          staticMapperClassNames = arrayOf(StaticInput::class.java.name),
          runtimeMapperClassNames = emptyArray(),
          singleLocatorClassNames = emptyArray(),
          batchLocatorClassNames = emptyArray(),
        ).value
    val mapped =
      staticPart.autoMapper.createOrUpdateObjectByInput(
        mapperSpec = StaticInput::class,
        allowedCreationObjectClasses = setOf(StaticEntity::class),
        input = StaticInput("value"),
      )

    assertEquals(StaticValue("static-value"), mapped.value)
  }

  @Test
  fun `CDI converter contract is validated while static blueprint is built`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        AutoMapperRecorder()
          .createStaticPart(
            staticMapperClassNames = emptyArray(),
            runtimeMapperClassNames = arrayOf(IncompatibleDiInput::class.java.name),
            singleLocatorClassNames = emptyArray(),
            batchLocatorClassNames = emptyArray(),
          )
      }

    assertTrue(error.message.orEmpty().contains("First argument of converter"))
  }

  @Test
  fun `runtime bean creator binds injection points and returns complete mapper`() {
    val dependency = DiDependency("synthetic-")
    val repository = DiEntityRepository()
    val recorder = DiValidationRecorder()
    val namingStrategy = DiNamingStrategy(dependency)
    val converter = DiTypeConverter(dependency)
    val entityGetter = DiEntityGetter(repository)
    val components =
      mapOf<KClass<*>, Any>(
        DiNamingStrategy::class to namingStrategy,
        DiTypeConverter::class to converter,
        DiUpdateValidator::class to DiUpdateValidator(recorder),
        DiEntityGetter::class to entityGetter,
      )

    @Suppress("UNCHECKED_CAST")
    val mapperContext =
      Mockito.mock(SyntheticCreationalContext::class.java) as
        SyntheticCreationalContext<AutoMapInputClassInfoProvider>
    components.forEach { (componentClass, component) ->
      @Suppress("UNCHECKED_CAST")
      Mockito.`when`(
        mapperContext.getInjectedReference(componentClass.java as Class<Any>)
      ).thenReturn(component)
    }
    val staticPart =
      AutoMapperRecorder()
        .createStaticPart(
          staticMapperClassNames = emptyArray(),
          runtimeMapperClassNames = arrayOf(DiInput::class.java.name),
          singleLocatorClassNames = emptyArray(),
          batchLocatorClassNames = emptyArray(),
        ).value
    assertEquals(0, namingStrategy.invocations)
    Mockito.`when`(mapperContext.getInjectedReference(AutoMapperStaticPart::class.java)).thenReturn(staticPart)

    val runtimeProviderCreator =
      AutoMapperRecorder().createRuntimeMapperBeanCreator(DiInput::class.java.name)
    val lazyRuntimeProvider = lazyProvider { runtimeProviderCreator.apply(mapperContext).get() }

    val mapper = createAutoMapper(staticPart, DiInput::class, lazyRuntimeProvider)

    assertEquals(0, namingStrategy.invocations)
    assertEquals(0, converter.invocations)
    assertEquals(0, entityGetter.invocations)
    assertEquals(emptyList(), recorder.values)
    components.keys.forEach { componentClass ->
      @Suppress("UNCHECKED_CAST")
      Mockito.verify(mapperContext, Mockito.never())
        .getInjectedReference(componentClass.java as Class<Any>)
    }

    val entity = DiEntity(id = "synthetic-1", name = DiValue("before"))
    repository.entities[entity.id] = entity

    mapper.createOrUpdateObjectByInput(
      mapperSpec = DiInput::class,
      allowedUpdateObjectClasses = setOf(DiEntity::class),
      input = DiInput(id = entity.id, rawName = "value"),
    )

    assertEquals(DiValue("synthetic-value"), entity.name)
    val namingInvocationsAfterFirstMapperCall = namingStrategy.invocations
    assertTrue(namingInvocationsAfterFirstMapperCall > 0)
    assertEquals(1, converter.invocations)
    assertEquals(1, entityGetter.invocations)

    mapper.createOrUpdateObjectByInput(
      mapperSpec = DiInput::class,
      allowedUpdateObjectClasses = setOf(DiEntity::class),
      input = DiInput(id = entity.id, rawName = "second"),
    )

    assertEquals(namingInvocationsAfterFirstMapperCall, namingStrategy.invocations)
    assertEquals(2, converter.invocations)
    assertEquals(2, entityGetter.invocations)
    components.keys.forEach { componentClass ->
      @Suppress("UNCHECKED_CAST")
      Mockito.verify(mapperContext, Mockito.times(1))
        .getInjectedReference(componentClass.java as Class<Any>)
    }
  }

  @Test
  fun `CDI mapper metadata is assembled by synthetic bean creator instead of AutoMapper`() {
    val dependency = DiDependency("lazy-")
    val namingStrategy = DiNamingStrategy(dependency)
    val components =
      mapOf<KClass<*>, Any>(
        DiNamingStrategy::class to namingStrategy,
        DiTypeConverter::class to DiTypeConverter(dependency),
        DiUpdateValidator::class to DiUpdateValidator(DiValidationRecorder()),
        DiEntityGetter::class to DiEntityGetter(DiEntityRepository()),
      )

    @Suppress("UNCHECKED_CAST")
    val mapperContext =
      Mockito.mock(SyntheticCreationalContext::class.java) as
        SyntheticCreationalContext<AutoMapInputClassInfoProvider>
    components.forEach { (componentClass, component) ->
      @Suppress("UNCHECKED_CAST")
      Mockito.`when`(
        mapperContext.getInjectedReference(componentClass.java as Class<Any>)
      ).thenReturn(component)
    }
    val staticPart =
      AutoMapperRecorder()
        .createStaticPart(
          staticMapperClassNames = arrayOf(StaticInput::class.java.name),
          runtimeMapperClassNames = arrayOf(DiInput::class.java.name),
          singleLocatorClassNames = emptyArray(),
          batchLocatorClassNames = emptyArray(),
        ).value
    Mockito.`when`(mapperContext.getInjectedReference(AutoMapperStaticPart::class.java)).thenReturn(staticPart)

    val runtimeProviderCreator =
      AutoMapperRecorder().createRuntimeMapperBeanCreator(DiInput::class.java.name)
    val runtimeProvider = lazyProvider { runtimeProviderCreator.apply(mapperContext).get() }

    val mapper = createAutoMapper(staticPart, DiInput::class, runtimeProvider)
    assertEquals(0, namingStrategy.invocations)

    val mapped =
      mapper.createOrUpdateObjectByInput(
        mapperSpec = StaticInput::class,
        allowedCreationObjectClasses = setOf(StaticEntity::class),
        input = StaticInput("value"),
      )

    assertEquals(StaticValue("static-value"), mapped.value)
    assertEquals(0, namingStrategy.invocations)
    components.keys.forEach { componentClass ->
      @Suppress("UNCHECKED_CAST")
      Mockito.verify(mapperContext, Mockito.never())
        .getInjectedReference(componentClass.java as Class<Any>)
    }
  }

  @Test
  fun `application scoped components are resolved once and reused at runtime`() {
    val dependency = DiDependency("injected-")
    val repository = DiEntityRepository()
    val validationRecorder = DiValidationRecorder()
    val components =
      mapOf(
        DiNamingStrategy::class to DiNamingStrategy(dependency),
        DiTypeConverter::class to DiTypeConverter(dependency),
        DiUpdateValidator::class to DiUpdateValidator(validationRecorder),
        DiEntityGetter::class to DiEntityGetter(repository),
      )
    val resolutions = mutableMapOf<KClass<*>, Int>()
    val mapper =
      AutoMapMapperBuilder { componentClass ->
        resolutions[componentClass] = resolutions.getOrDefault(componentClass, 0) + 1
        components[componentClass]
      }.build(listOf(DiInput::class.java))

    val entity = DiEntity(id = "entity-1", name = DiValue("before"))
    repository.entities[entity.id] = entity

    repeat(2) { invocation ->
      val mapped =
        mapper.createOrUpdateObjectByInput(
          mapperSpec = DiInput::class,
          allowedUpdateObjectClasses = setOf(DiEntity::class),
          input = DiInput(id = entity.id, rawName = "name-$invocation"),
        )

      assertSame(entity, mapped)
    }

    assertEquals(DiValue("injected-name-1"), entity.name)
    assertEquals(
      listOf(DiValue("injected-name-0"), DiValue("injected-name-1")),
      validationRecorder.values,
    )
    assertEquals(components.keys.associateWith { 1 }, resolutions)
  }

  @Test
  fun `application scoped existing entity lookup receives injected dependency`() {
    val repository = DiLocatedEntityRepository()
    val lookup = DiExistingEntityLookup(repository)
    @Suppress("UNCHECKED_CAST")
    val mapperContext =
      Mockito.mock(SyntheticCreationalContext::class.java) as
        SyntheticCreationalContext<AutoMapInputClassInfoProvider>
    Mockito.`when`(mapperContext.getInjectedReference(DiExistingEntityLookup::class.java)).thenReturn(lookup)
    val staticPart =
      AutoMapperRecorder()
        .createStaticPart(
          staticMapperClassNames = emptyArray(),
          runtimeMapperClassNames = arrayOf(DiLocatedInput::class.java.name),
          singleLocatorClassNames = arrayOf(DiExistingEntityLookup::class.java.name),
          batchLocatorClassNames = emptyArray(),
        ).value
    Mockito.`when`(mapperContext.getInjectedReference(AutoMapperStaticPart::class.java)).thenReturn(staticPart)
    val runtimeProviderCreator =
      AutoMapperRecorder().createRuntimeMapperBeanCreator(DiLocatedInput::class.java.name)
    val mapper =
      createAutoMapper(
        staticPart,
        DiLocatedInput::class,
        lazyProvider { runtimeProviderCreator.apply(mapperContext).get() },
      )

    val entity = DiLocatedEntity(key = "natural-key", value = "before")
    repository.entities[entity.key] = entity

    val mapped =
      mapper.createOrUpdateObjectByInput(
        mapperSpec = DiLocatedInput::class,
        allowedUpdateObjectClasses = setOf(DiLocatedEntity::class),
        input = DiLocatedInput(key = entity.key, value = "after"),
      )

    assertSame(entity, mapped)
    assertEquals("after", entity.value)
    assertEquals(1, lookup.findInvocations)
    Mockito.verify(mapperContext, Mockito.times(1))
      .getInjectedReference(DiExistingEntityLookup::class.java)
  }

  @Test
  fun `calling one runtime mapper does not resolve components of another runtime mapper`() {
    val dependency = DiDependency("unused-")
    val unusedComponents =
      mapOf<KClass<*>, Any>(
        DiNamingStrategy::class to DiNamingStrategy(dependency),
        DiTypeConverter::class to DiTypeConverter(dependency),
        DiUpdateValidator::class to DiUpdateValidator(DiValidationRecorder()),
        DiEntityGetter::class to DiEntityGetter(DiEntityRepository()),
      )
    @Suppress("UNCHECKED_CAST")
    val unusedMapperContext =
      Mockito.mock(SyntheticCreationalContext::class.java) as
        SyntheticCreationalContext<AutoMapInputClassInfoProvider>
    unusedComponents.forEach { (componentClass, component) ->
      @Suppress("UNCHECKED_CAST")
      Mockito.`when`(
        unusedMapperContext.getInjectedReference(componentClass.java as Class<Any>)
      ).thenReturn(component)
    }

    val repository = DiLocatedEntityRepository()
    val lookup = DiExistingEntityLookup(repository)
    @Suppress("UNCHECKED_CAST")
    val calledMapperContext =
      Mockito.mock(SyntheticCreationalContext::class.java) as
        SyntheticCreationalContext<AutoMapInputClassInfoProvider>
    Mockito.`when`(calledMapperContext.getInjectedReference(DiExistingEntityLookup::class.java))
      .thenReturn(lookup)

    val staticPart =
      AutoMapperRecorder()
        .createStaticPart(
          staticMapperClassNames = emptyArray(),
          runtimeMapperClassNames =
            arrayOf(DiInput::class.java.name, DiLocatedInput::class.java.name),
          singleLocatorClassNames = arrayOf(DiExistingEntityLookup::class.java.name),
          batchLocatorClassNames = emptyArray(),
        ).value
    Mockito.`when`(unusedMapperContext.getInjectedReference(AutoMapperStaticPart::class.java))
      .thenReturn(staticPart)
    Mockito.`when`(calledMapperContext.getInjectedReference(AutoMapperStaticPart::class.java))
      .thenReturn(staticPart)

    val recorder = AutoMapperRecorder()
    val mapper =
      createAutoMapper(
        staticPart,
        mapOf(
          DiInput::class to
            lazyProvider {
              recorder.createRuntimeMapperBeanCreator(DiInput::class.java.name)
                .apply(unusedMapperContext)
                .get()
            },
          DiLocatedInput::class to
            lazyProvider {
              recorder.createRuntimeMapperBeanCreator(DiLocatedInput::class.java.name)
                .apply(calledMapperContext)
                .get()
            },
        ),
      )
    val entity = DiLocatedEntity(key = "called", value = "before")
    repository.entities[entity.key] = entity

    mapper.createOrUpdateObjectByInput(
      mapperSpec = DiLocatedInput::class,
      allowedUpdateObjectClasses = setOf(DiLocatedEntity::class),
      input = DiLocatedInput(key = entity.key, value = "after"),
    )

    unusedComponents.keys.forEach { componentClass ->
      @Suppress("UNCHECKED_CAST")
      Mockito.verify(unusedMapperContext, Mockito.never())
        .getInjectedReference(componentClass.java as Class<Any>)
    }
    Mockito.verify(calledMapperContext, Mockito.times(1))
      .getInjectedReference(DiExistingEntityLookup::class.java)
  }

  private fun createAutoMapper(
    staticPart: AutoMapperStaticPart,
    mapperClass: KClass<*>,
    runtimeProvider: AutoMapInputClassInfoProvider,
  ): AutoMapper = createAutoMapper(staticPart, mapOf(mapperClass to runtimeProvider))

  private fun createAutoMapper(
    staticPart: AutoMapperStaticPart,
    runtimeProviders: Map<KClass<*>, AutoMapInputClassInfoProvider>,
  ): AutoMapper {
    @Suppress("UNCHECKED_CAST")
    val autoMapperContext =
      Mockito.mock(SyntheticCreationalContext::class.java) as SyntheticCreationalContext<AutoMapper>
    Mockito.`when`(autoMapperContext.getInjectedReference(AutoMapperStaticPart::class.java))
      .thenReturn(staticPart)
    runtimeProviders.forEach { (mapperClass, runtimeProvider) ->
      Mockito.`when`(
        autoMapperContext.getInjectedReference(
          AutoMapInputClassInfoProvider::class.java,
          NamedLiteral.of(mapperClass.java.name),
        )
      ).thenReturn(runtimeProvider)
    }

    return AutoMapperRecorder()
      .createRuntimeAutoMapperBeanCreator(
        runtimeProviders.keys.map { mapperClass -> mapperClass.java.name }.toTypedArray()
      )
      .apply(autoMapperContext)
  }

  private fun lazyProvider(factory: () -> Any): AutoMapInputClassInfoProvider {
    val value = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)
    return AutoMapInputClassInfoProvider { value.value }
  }
}

class DiDependency(val prefix: String)

data class StaticValue(val value: String)

class StaticEntity(
  val value: StaticValue,
) {
  companion object {
    fun create(value: StaticValue): StaticEntity = StaticEntity(value)
  }
}

object StaticTypeConverter : AutoMapTypeConverter<String, StaticValue> {
  override fun convert(value: String): StaticValue = StaticValue("static-$value")
}

@AutoMapObjectFromInput(constructMethod = "create")
class StaticInput(
  @get:AutoMapField(typeConverter = StaticTypeConverter::class)
  var value: String,
) : AutoMapperSpecTo<StaticEntity>

data class DiValue(val value: String)

class DiEntity(
  val id: String,
  var name: DiValue,
) {
  companion object {
    fun create(name: DiValue): DiEntity = DiEntity("created", name)
  }
}

class DiEntityRepository {
  val entities = mutableMapOf<String, DiEntity>()
}

class DiValidationRecorder {
  val values = mutableListOf<DiValue>()
}

@ApplicationScoped
class DiNamingStrategy(
  private val dependency: DiDependency,
) : AutoMapFieldNamingStrategy {
  var invocations: Int = 0

  override fun getObjectFieldName(inputName: String): String {
    invocations++
    check(dependency.prefix.isNotEmpty())
    return "name"
  }
}

@ApplicationScoped
class DiTypeConverter(
  private val dependency: DiDependency,
) : AutoMapTypeConverter<String, DiValue> {
  var invocations: Int = 0

  override fun convert(value: String): DiValue {
    invocations++
    return DiValue(dependency.prefix + value)
  }
}

@ApplicationScoped
class DiUpdateValidator(
  private val recorder: DiValidationRecorder,
) : AutoMapFieldUpdateValidator<DiEntity, DiValue, DiValue, String> {
  override fun validate(
    parent: DiEntity,
    currentValue: DiValue,
    newValue: DiValue,
    inputValue: String,
    fieldName: String,
  ) {
    assertEquals("rawName", fieldName)
    recorder.values += newValue
  }
}

@ApplicationScoped
class DiEntityGetter(
  private val repository: DiEntityRepository,
) {
  var invocations: Int = 0

  fun get(entityClass: KClass<*>, id: String): Any? {
    invocations++
    assertEquals(DiEntity::class, entityClass)
    return repository.entities[id]
  }
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  idField = "id",
  objectGetterClass = DiEntityGetter::class,
  allowUpdate = true,
)
class DiInput(
  var id: String?,
  @get:AutoMapField(
    namingStrategy = DiNamingStrategy::class,
    typeConverter = DiTypeConverter::class,
    updateValidatorClass = DiUpdateValidator::class,
  )
  var rawName: String,
) : AutoMapperSpecTo<DiEntity>

@ApplicationScoped
class IncompatibleDiTypeConverter : AutoMapTypeConverter<Int, DiValue> {
  override fun convert(value: Int): DiValue = DiValue(value.toString())
}

@AutoMapObjectFromInput(
  constructMethod = "create",
  allowUpdate = false,
)
class IncompatibleDiInput(
  @get:AutoMapField(
    constructParameterName = "name",
    typeConverter = IncompatibleDiTypeConverter::class,
  )
  var rawName: String,
) : AutoMapperSpecTo<DiEntity>

class DiLocatedEntity(
  var key: String,
  var value: String,
)

class DiLocatedEntityRepository {
  val entities = mutableMapOf<String, DiLocatedEntity>()
}

@ApplicationScoped
class DiExistingEntityLookup(
  private val repository: DiLocatedEntityRepository,
) : AutoMapExistingEntityLookup<DiLocatedInput, DiLocatedEntity, String, Any, Any> {
  var findInvocations: Int = 0

  override fun getLookupKey(
    input: DiLocatedInput,
    context: AutoMapExistingEntityLookupContext<DiLocatedInput, DiLocatedEntity, Any, Any>,
  ): String = input.key

  override fun findExisting(
    input: DiLocatedInput,
    context: AutoMapExistingEntityLookupContext<DiLocatedInput, DiLocatedEntity, Any, Any>,
  ): DiLocatedEntity? {
    findInvocations++
    return repository.entities[input.key]
  }
}

@AutoMapObjectFromInput(
  allowCreate = false,
  allowUpdate = true,
  existingEntityLookupClasses = [DiExistingEntityLookup::class],
)
class DiLocatedInput(
  var key: String,
  var value: String,
) : AutoMapperSpecTo<DiLocatedEntity>
