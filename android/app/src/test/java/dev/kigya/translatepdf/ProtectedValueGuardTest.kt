package dev.kigya.translatepdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedValueGuardTest {
  @Test
  fun cjkUnitAdjacentToPreservedNumberDoesNotCauseFalseMismatch() {
    assertFalse(ProtectedValueGuard.hasMismatch("Максимальное давление: 20 бар.", "最大压力：20巴。"))
  }

  @Test
  fun changedNumberAdjacentToCjkUnitRemainsProtected() {
    assertTrue(ProtectedValueGuard.hasMismatch("Максимальное давление: 20 бар.", "最大压力：21巴。"))
  }

  @Test
  fun knownLatinUnitsAndPartCodesRemainProtected() {
    assertFalse(ProtectedValueGuard.hasMismatch("Pressure 20 MPa at P0400.", "Druck 20 MPa bei P0400."))
    assertTrue(ProtectedValueGuard.hasMismatch("Pressure 20 MPa at P0400.", "Druck 21 MPa bei P0401."))
  }

  @Test
  fun numbersAndPartCodesAdjacentToHanOnEitherSideRemainProtected() {
    assertEquals(
      ProtectedValueGuard.protectedValues("Pressure 20 bar at A12."),
      ProtectedValueGuard.protectedValues("压力20巴，检查A12阀门。"),
    )
    assertTrue(ProtectedValueGuard.hasMismatch("Pressure 20 bar at A12.", "压力21巴，检查A13阀门。"))
    assertTrue(ProtectedValueGuard.hasMismatch("Pressure 20 bar at A12.", "压力20巴，检查A13阀门。"))
    assertTrue(ProtectedValueGuard.hasMismatch("Pressure 20 bar at A12.", "压力21巴，检查A12阀门。"))
  }
}
