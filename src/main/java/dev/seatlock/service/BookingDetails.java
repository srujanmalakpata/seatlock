package dev.seatlock.service;

import dev.seatlock.domain.Booking;
import dev.seatlock.domain.Seat;
import java.util.List;

/** A booking together with its seats, in seat-map order. */
public record BookingDetails(Booking booking, List<Seat> seats) {}
