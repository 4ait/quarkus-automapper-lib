package ru.code4a.quarkus.automapper.interfaces

/**
 * Field naming strategy for automatic mapping.
 * Defines rules for converting field names from Input objects to target objects.
 *
 * This interface is used to provide flexible field name mapping strategies that can be assigned
 * via the @AutoMapField annotation. It allows for custom field name transformations during the
 * automatic mapping process.
 */
interface AutoMapFieldNamingStrategy {
  /**
   * Gets the target object's field name based on an Input object property.
   *
   * This method is called during the mapping process to determine the corresponding
   * field name in the target object. Implementations can apply custom naming conventions,
   * transformations, or validation rules.
   *
   * @return The field name to be used in the target object
   * @throws IllegalArgumentException if the property name is null
   * @throws IllegalStateException if the property name does not meet the strategy's requirements
   *
   * Example:
   * ```
   * // Default strategy - returns the same name
   * getObjectFieldName("userNameProperty") // returns "userName"
   *
   * // EntityReference strategy - removes Id suffix
   * getObjectFieldName("userIdProperty") // returns "user"
   * ```
   */
  fun getObjectFieldName(inputName: String): String
}
