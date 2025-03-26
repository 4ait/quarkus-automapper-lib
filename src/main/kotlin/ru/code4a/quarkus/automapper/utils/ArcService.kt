package ru.code4a.quarkus.automapper.utils

import io.quarkus.arc.Arc

/**
 * Internal utility class for accessing CDI bean instances from Quarkus Arc container.
 * Provides type-safe access to CDI beans.
 */
internal object ArcService {
  /**
   * Gets an instance of the specified type from the Arc container.
   * Uses reified type parameter for type-safe bean resolution.
   *
   * @return An instance of the specified type T
   */
  inline fun <reified T> get(): T {
    return getFromClass(T::class.java)
  }

  /**
   * Gets an instance of the specified class from the Arc container.
   *
   * @param clazz The class object representing the bean type to retrieve
   * @return An instance of the specified class
   */
  fun <T> getFromClass(clazz: Class<T>): T {
    return Arc
      .container()
      .instance(clazz)
      .get()
  }
}
