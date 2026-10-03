package dev.seatlock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SectionSpecTest {

  @ParameterizedTest
  @CsvSource({
    "FLOOR, 0, 10, 100",
    "FLOOR, 10, 0, 100",
    "FLOOR, -100, 100, 100",
    "FLOOR, 1, 1, -1",
    "FLOOR, 1, 1, 100000001",
    "'  ', 1, 1, 100"
  })
  void rejectsInvalidSections(String name, int rows, int seatsPerRow, long priceCents) {
    assertThatThrownBy(() -> new SectionSpec(name, rows, seatsPerRow, priceCents))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void acceptsBoundaryValues() {
    assertThat(new SectionSpec("A", 1, 1, 0).priceCents()).isZero();
    assertThat(new SectionSpec("A", 1, 1, SectionSpec.MAX_PRICE_CENTS).priceCents())
        .isEqualTo(100_000_000L);
  }

  @Test
  void negativeSectionCannotOffsetAnotherSectionInTheSeatLimit() {
    // Regression: -100 x 100 seats plus a 15,000-seat section sums to 5,000 and would
    // bypass the 10,000-seat limit unless the domain rejects negative dimensions.
    assertThatThrownBy(
            () ->
                SeatMapLayout.generate(
                    UUID.randomUUID(),
                    List.of(
                        new SectionSpec("NEG", -100, 100, 0), new SectionSpec("BIG", 150, 100, 0))))
        .isInstanceOf(InvalidRequestException.class);
  }
}
