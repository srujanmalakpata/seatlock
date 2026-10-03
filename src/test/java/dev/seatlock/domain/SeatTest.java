package dev.seatlock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeatTest {

  private final UUID holdA = UUID.randomUUID();
  private final UUID holdB = UUID.randomUUID();

  private Seat seat() {
    return new Seat(UUID.randomUUID(), UUID.randomUUID(), "FLOOR", 2, "B", 7, 4200);
  }

  @Test
  void newSeatIsAvailableAndLabelled() {
    Seat seat = seat();
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    assertThat(seat.getHoldId()).isNull();
    assertThat(seat.label()).isEqualTo("FLOOR-B7");
  }

  @Test
  void holdThenBookThenRelease() {
    Seat seat = seat();
    seat.holdFor(holdA);
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
    assertThat(seat.getHoldId()).isEqualTo(holdA);

    seat.book(holdA);
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);

    seat.releaseFrom(holdA);
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    assertThat(seat.getHoldId()).isNull();
  }

  @Test
  void cannotHoldASeatThatIsAlreadyHeld() {
    Seat seat = seat();
    seat.holdFor(holdA);

    assertThatThrownBy(() -> seat.holdFor(holdB))
        .isInstanceOf(SeatUnavailableException.class)
        .satisfies(
            e ->
                assertThat(((SeatUnavailableException) e).seatIds()).containsExactly(seat.getId()));
    assertThat(seat.getHoldId()).isEqualTo(holdA);
  }

  @Test
  void onlyTheOwningHoldCanBookOrRelease() {
    Seat seat = seat();
    seat.holdFor(holdA);

    assertThatThrownBy(() -> seat.book(holdB)).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> seat.releaseFrom(holdB)).isInstanceOf(InvalidStateException.class);
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
  }

  @Test
  void cannotBookOrReleaseAnAvailableSeat() {
    Seat seat = seat();
    assertThatThrownBy(() -> seat.book(holdA)).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> seat.releaseFrom(holdA)).isInstanceOf(InvalidStateException.class);
  }
}
