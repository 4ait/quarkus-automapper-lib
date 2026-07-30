package ru.code4a.quarkus.automapper.services

import jakarta.enterprise.context.ApplicationScoped
import ru.code4a.quarkus.automapper.meta.InputClassInfo
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation

internal class AutoMapComponentReference<COMPONENT : Any> private constructor(
  val componentClass: KClass<COMPONENT>?,
  fixedValue: COMPONENT?,
) {
  private var value: COMPONENT? = fixedValue

  fun bind(cdiLookup: (KClass<*>) -> Any?) {
    val requiredClass = componentClass ?: return
    val resolved = cdiLookup(requiredClass)
      ?: error("Cannot resolve @ApplicationScoped component $requiredClass from CDI")
    require(requiredClass.isInstance(resolved)) {
      "Resolved component $resolved is not an instance of $requiredClass"
    }
    @Suppress("UNCHECKED_CAST")
    value = resolved as COMPONENT
  }

  fun get(): COMPONENT {
    return value ?: error("CDI component $componentClass was used before its mapper was initialized")
  }

  companion object {
    fun <COMPONENT : Any> fixed(value: COMPONENT): AutoMapComponentReference<COMPONENT> =
      AutoMapComponentReference(componentClass = null, fixedValue = value)

    fun <COMPONENT : Any> cdi(
      componentClass: KClass<COMPONENT>,
    ): AutoMapComponentReference<COMPONENT> =
      AutoMapComponentReference(componentClass = componentClass, fixedValue = null)
  }
}

/** One deduplicated, pre-indexed CDI component table per mapper blueprint. */
internal class AutoMapComponentReferences {
  private val references = linkedMapOf<KClass<*>, AutoMapComponentReference<*>>()

  val componentClasses: Set<KClass<*>>
    get() = references.keys

  fun <COMPONENT : Any> reference(
    componentClass: KClass<COMPONENT>,
    allowNoArgConstruction: Boolean = false,
    missingMessage: () -> String,
  ): AutoMapComponentReference<COMPONENT> {
    componentClass.objectInstance?.let { return AutoMapComponentReference.fixed(it) }

    if (componentClass.findAnnotation<ApplicationScoped>() != null) {
      @Suppress("UNCHECKED_CAST")
      return references.getOrPut(componentClass) {
        AutoMapComponentReference.cdi(componentClass)
      } as AutoMapComponentReference<COMPONENT>
    }

    if (allowNoArgConstruction) {
      return AutoMapComponentReference.fixed(componentClass.createInstance())
    }

    error(missingMessage())
  }

  fun bind(cdiLookup: (KClass<*>) -> Any?) {
    references.values.forEach { reference -> reference.bind(cdiLookup) }
  }
}

/** A build-time prepared value that can be materialized without performing reflection. */
internal class AutoMapBinding<T> private constructor(
  val isFixed: Boolean,
  val componentClasses: Set<KClass<*>>,
  private val fixedValue: T?,
  private val binder: ((AutoMapComponentResolver) -> T)?,
) {
  fun bind(componentResolver: AutoMapComponentResolver): T {
    if (isFixed) {
      @Suppress("UNCHECKED_CAST")
      return fixedValue as T
    }
    return checkNotNull(binder).invoke(componentResolver)
  }

  fun fixedValue(): T {
    check(isFixed) { "Binding is not fixed" }
    @Suppress("UNCHECKED_CAST")
    return fixedValue as T
  }

  fun <R> map(transform: (T) -> R): AutoMapBinding<R> {
    return if (isFixed) {
      fixed(transform(fixedValue()))
    } else {
      dynamic(componentClasses) { resolver -> transform(bind(resolver)) }
    }
  }

  fun <U, R> zip(other: AutoMapBinding<U>, transform: (T, U) -> R): AutoMapBinding<R> {
    return if (isFixed && other.isFixed) {
      fixed(transform(fixedValue(), other.fixedValue()))
    } else {
      dynamic(componentClasses + other.componentClasses) { resolver ->
        transform(bind(resolver), other.bind(resolver))
      }
    }
  }

  companion object {
    fun <T> fixed(value: T): AutoMapBinding<T> = AutoMapBinding(true, emptySet(), value, null)

    fun <T> dynamic(
      componentClasses: Set<KClass<*>> = emptySet(),
      binder: (AutoMapComponentResolver) -> T,
    ): AutoMapBinding<T> = AutoMapBinding(false, componentClasses, null, binder)
  }
}

internal fun <T, R> combineBindings(
  bindings: List<AutoMapBinding<T>>,
  transform: (List<T>) -> R,
): AutoMapBinding<R> {
  return if (bindings.all(AutoMapBinding<T>::isFixed)) {
    AutoMapBinding.fixed(transform(bindings.map(AutoMapBinding<T>::fixedValue)))
  } else {
    AutoMapBinding.dynamic(bindings.flatMapTo(linkedSetOf()) { it.componentClasses }) { resolver ->
      transform(bindings.map { it.bind(resolver) })
    }
  }
}

/** Structural mapper metadata produced during STATIC_INIT and bound to CDI references later. */
internal class AutoMapperBlueprint(
  private val preparedMappers: Map<Class<*>, AutoMapPreparedMapperBlueprint>,
) {
  private val mapperClassesByName = preparedMappers.keys.associateBy { mapperClass -> mapperClass.name }

  val mapperClasses: Set<Class<*>>
    get() = preparedMappers.keys

  fun mapperClass(mapperClassName: String): Class<*> {
    return mapperClassesByName[mapperClassName]
      ?: error("Cannot find runtime blueprint for mapper spec $mapperClassName")
  }

  fun componentClasses(mapperClass: Class<*>): Set<KClass<*>> {
    val preparedMapper = preparedMapper(mapperClass)
    return preparedMapper.componentReferences.componentClasses +
      preparedMapper.inputClassInfoBinding.componentClasses
  }

  fun bindMapper(
    mapperClass: Class<*>,
    cdiLookup: (KClass<*>) -> Any?,
  ): InputClassInfo {
    val preparedMapper = preparedMapper(mapperClass)
    preparedMapper.componentReferences.bind(cdiLookup)
    val componentResolver = AutoMapComponentResolver(cdiLookup)
    return preparedMapper.inputClassInfoBinding.bind(componentResolver)
  }

  private fun preparedMapper(mapperClass: Class<*>): AutoMapPreparedMapperBlueprint {
    return preparedMappers[mapperClass]
      ?: error("Cannot find runtime blueprint for mapper spec $mapperClass")
  }
}

internal class AutoMapPreparedMapperBlueprint(
  val inputClassInfoBinding: AutoMapBinding<InputClassInfo>,
  val componentReferences: AutoMapComponentReferences,
)

internal fun <COMPONENT : Any> componentBinding(
  componentClass: KClass<COMPONENT>,
  allowNoArgConstruction: Boolean = false,
  missingMessage: () -> String,
): AutoMapBinding<COMPONENT> {
  componentClass.objectInstance?.let { return AutoMapBinding.fixed(it) }

  if (componentClass.findAnnotation<ApplicationScoped>() != null) {
    return AutoMapBinding.dynamic(setOf(componentClass)) { resolver ->
      resolver.resolveCdiReferenceOrNull(componentClass) ?: error(missingMessage())
    }
  }

  if (allowNoArgConstruction) {
    return AutoMapBinding.fixed(componentClass.createInstance())
  }

  error(missingMessage())
}
