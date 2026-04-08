package ru.code4a.quarkus.automapper.exceptions

import kotlin.test.Test
import kotlin.test.assertEquals

class InputAutomapperExceptionsTest {

  @Test
  fun `should include fieldName in exception messages`() {
    val cases = listOf(
      MissingRequiredFieldInputAutomapperException("requiredField", "TestInput") to
        "Missing required field: className='TestInput', fieldName='requiredField'.",
      FieldCannotBeNullInputAutomapperException("nullableField", "TestInput") to
        "Field cannot be null: className='TestInput', fieldName='nullableField'.",
      FieldCannotBeUpdatedInputAutomapperException("readonlyField", "TestInput") to
        "Field cannot be updated: className='TestInput', fieldName='readonlyField'.",
      CannotUpdateEntityInEmptyFieldInputAutomapperException("nestedField", "TestInput") to
        "Cannot update entity in empty field: className='TestInput', fieldName='nestedField'.",
      FieldIsNotSupportedForCreateInputAutomapperException("unsupportedField", "TestInput") to
        "Field is not supported for create: className='TestInput', fieldName='unsupportedField'."
    )

    cases.forEach { (exception, expectedMessage) ->
      assertEquals(expectedMessage, exception.message)
    }
  }

  @Test
  fun `should keep one-argument constructor format`() {
    assertEquals(
      "Field cannot be null: fieldName='legacyField'.",
      FieldCannotBeNullInputAutomapperException("legacyField").message
    )
  }
}
