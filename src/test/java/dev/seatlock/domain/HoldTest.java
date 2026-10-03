package dev.seatlock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldTest {

  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(5);

  private Hold hold() {
    return Hold.place(UUID.randomUUID(), "cust-1", List.of(UUID.randomUUID()), NOW, TTL);
  }

  @Test
  void placedHoldIsActiveUntilTtlElapses() {
    Hold hold = hold();
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    assertThat(hold.getExpiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(hold.isExpiredAt(NOW.plus(TTL).minusMillis(1))).isFalse();
    assertThat(hold.isExpiredAt(NOW.plus(TTL))).isTrue();
  }

  @Test
  void requiresAtLeastOneSeat() {
    assertThatThrownBy(() -> Hold.place(UUID.randomUUID(), "c", List.of(), NOW, TTL))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void confirmBeforeExpiry() {
    Hold hold = hold();
    hold.confirm(NOW.plusSeconds(10));
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
  }

  @Test
  void confirmAtOrAfterExpiryFailsEvenBeforeTheSweeperRuns() {
    Hold hold = hold();
    assertThatThrownBy(() -> hold.confirm(NOW.plus(TTL))).isInstanceOf(HoldExpiredException.class);
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
  }

  @Test
  void expiredHoldCannotBeConfirmed() {
    Hold hold = hold();
    hold.expire(NOW.plus(TTL));
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    assertThatThrownBy(() -> hold.confirm(NOW)).isInstanceOf(HoldExpiredException.class);
  }

  @Test
  void cannotExpireBeforeDue() {
    Hold hold = hold();
    assertThatThrownBy(() -> hold.expire(NOW.plusSeconds(1)))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void endedHoldsRejectFurtherTransitions() {
    Hold confirmed = hold();
    confirmed.confirm(NOW);
    assertThatThrownBy(confirmed::release).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> confirmed.confirm(NOW)).isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> confirmed.expire(NOW.plus(TTL)))
        .isInstanceOf(InvalidStateException.class);

    Hold released = hold();
    released.release();
    assertThat(released.getStatus()).isEqualTo(HoldStatus.RELEASED);
    assertThatThrownBy(() -> released.confirm(NOW)).isInstanceOf(InvalidStateException.class);
  }

  @Test
  void bookingRequiresConfirmedHoldAndCancelsOnce() {
    Hold hold = hold();
    assertThatThrownBy(() -> Booking.forConfirmedHold(hold, 100, NOW))
        .isInstanceOf(InvalidStateException.class);

    hold.confirm(NOW);
    Booking booking = Booking.forConfirmedHold(hold, 100, NOW);
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getHoldId()).isEqualTo(hold.getId());
    assertThat(booking.getTotalCents()).isEqualTo(100);

    booking.cancel(NOW.plusSeconds(60));
    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(booking.getCancelledAt()).isEqualTo(NOW.plusSeconds(60));
    assertThatThrownBy(() -> booking.cancel(NOW)).isInstanceOf(InvalidStateException.class);
  }
}
