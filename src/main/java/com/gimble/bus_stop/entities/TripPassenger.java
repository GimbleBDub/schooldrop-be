package com.gimble.bus_stop.entities;

import com.gimble.bus_stop.enums.PassengerStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A child on a trip, with the pickup/drop-off handover record parents and schools rely on. */
@Entity
@Table(name = "trip_passengers", uniqueConstraints = @UniqueConstraint(columnNames = {"trip_id", "child_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripPassenger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id", nullable = false)
    private Child child;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PassengerStatus status = PassengerStatus.EXPECTED;

    // Driver's side of each handover
    private Instant pickedUpAt;
    private Instant droppedOffAt;

    // Who the driver handed the child to at home, e.g. "Mother", "Helper (Mpho)"
    @Column(length = 120)
    private String handedTo;

    // The other side of each handover: a household member (at home) or school staff (at school)
    private Instant pickupConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pickup_confirmed_by")
    private User pickupConfirmedBy;

    private Instant dropoffConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dropoff_confirmed_by")
    private User dropoffConfirmedBy;

    // "Not received" / "that's not right" from the household or school — support is alerted
    private Instant disputedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "disputed_by")
    private User disputedBy;

    @Column(length = 500)
    private String disputeNote;

    // The kid confirmed on their own phone
    private Instant kidConfirmedPickupAt;
    private Instant kidConfirmedDropoffAt;
}
