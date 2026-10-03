package dev.seatlock.service;

import dev.seatlock.domain.Hold;
import dev.seatlock.domain.Seat;
import java.util.List;

/** A hold together with the seats it covers, in seat-map order. */
public record HoldDetails(Hold hold, List<Seat> seats) {}
