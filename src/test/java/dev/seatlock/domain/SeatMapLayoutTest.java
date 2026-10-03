package dev.seatlock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeatMapLayoutTest {

  private final UUID eventId = UUID.randomUUID();

  @ParameterizedTest
  @CsvSource({"1,A", "2,B", "26,Z", "27,AA", "28,AB", "52,AZ", "53,BA", "702,ZZ", "703,AAA"})
  void rowLabelsFollowSpreadsheetColumns(int index, String expected) {
    assertThat(SeatMapLayout.rowLabel(index)).isEqualTo(expected);
  }

  @Test
  void rowIndexMustBePositive() {
    assertThatThrownBy(() -> SeatMapLayout.rowLabel(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generatesEverySeatOfEverySectionInOrder() {
    List<Seat> seats =
        SeatMapLayout.generate(
            eventId,
            List.of(new SectionSpec("FLOOR", 2, 3, 5000), new SectionSpec("BALCONY", 1, 2, 2500)));

    assertThat(seats).hasSize(2 * 3 + 2);
    assertThat(seats)
        .extracting(Seat::label)
        .containsExactly(
            "FLOOR-A1",
            "FLOOR-A2",
            "FLOOR-A3",
            "FLOOR-B1",
            "FLOOR-B2",
            "FLOOR-B3",
            "BALCONY-A1",
            "BALCONY-A2");
    assertThat(seats).allSatisfy(s -> assertThat(s.getEventId()).isEqualTo(eventId));
    assertThat(seats).allSatisfy(s -> assertThat(s.isAvailable()).isTrue());
    assertThat(seats).extracting(Seat::getId).doesNotHaveDuplicates();
    assertThat(seats.get(7).getPriceCents()).isEqualTo(2500);
  }

  @Test
  void rejectsDuplicateSectionNames() {
    List<SectionSpec> sections =
        List.of(new SectionSpec("FLOOR", 1, 1, 0), new SectionSpec("FLOOR", 1, 1, 0));

    assertThatThrownBy(() -> SeatMapLayout.generate(eventId, sections))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("Duplicate section");
  }

  @Test
  void rejectsSeatMapsAboveTheLimit() {
    List<SectionSpec> sections =
        List.of(new SectionSpec("A", 100, 100, 0), new SectionSpec("B", 1, 1, 0));

    assertThatThrownBy(() -> SeatMapLayout.generate(eventId, sections))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("10001 seats");
  }

  @Test
  void rejectsEmptySeatMap() {
    assertThatThrownBy(() -> SeatMapLayout.generate(eventId, List.of()))
        .isInstanceOf(InvalidRequestException.class);
  }
}
