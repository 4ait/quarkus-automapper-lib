package ru.code4a.quarkus.automapper.services

import jakarta.enterprise.context.ApplicationScoped
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation

/** Resolves and caches user-provided mapper component references while a prepared blueprint is bound. */
internal class AutoMapComponentResolver(
  private val cdiLookup: ((KClass<*>) -> Any?)? = null,
) {
  private val instances = mutableMapOf<KClass<*>, Any?>()

  fun <COMPONENT : Any> resolveOrNull(componentClass: KClass<COMPONENT>): COMPONENT? {
    if (instances.containsKey(componentClass)) {
      @Suppress("UNCHECKED_CAST")
      return instances[componentClass] as COMPONENT?
    }

    val instance =
      if (componentClass.findAnnotation<ApplicationScoped>() != null) {
        resolveCdiReferenceOrNull(componentClass)
      } else {
        null
      }
        ?: componentClass.objectInstance

    require(instance == null || componentClass.isInstance(instance)) {
      "Resolved component $instance is not an instance of $componentClass"
    }

    instances[componentClass] = instance

    return instance
  }

  /** Resolves a class already identified as a CDI component during STATIC_INIT. */
  fun <COMPONENT : Any> resolveCdiReferenceOrNull(componentClass: KClass<COMPONENT>): COMPONENT? {
    if (instances.containsKey(componentClass)) {
      @Suppress("UNCHECKED_CAST")
      return instances[componentClass] as COMPONENT?
    }

    val instance = cdiLookup?.invoke(componentClass)
    require(instance == null || componentClass.isInstance(instance)) {
      "Resolved component $instance is not an instance of $componentClass"
    }
    instances[componentClass] = instance

    @Suppress("UNCHECKED_CAST")
    return instance as COMPONENT?
  }

  fun <COMPONENT : Any> resolveOrCreate(componentClass: KClass<COMPONENT>): COMPONENT {
    resolveOrNull(componentClass)?.let { return it }

    check(componentClass.findAnnotation<ApplicationScoped>() == null) {
      "Component $componentClass is annotated with @ApplicationScoped but cannot be resolved from CDI"
    }

    return componentClass.createInstance().also { instances[componentClass] = it }
  }
}
