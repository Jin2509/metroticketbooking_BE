package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.metroticket_api.metro.station.Station;

/** Nhật ký mỗi lần quẹt vé tại cổng soát vé (kể cả bị từ chối). */
@Entity
@Table(name = "ticket_validation")
public class TicketValidation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GateDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GateResult result;

    @Column(length = 255)
    private String reason;

    /** Số tiền phải thu bù khi vé lượt ra quá ga đã mua. */
    @Column(name = "fare_difference_vnd")
    private Integer fareDifferenceVnd;

    @Column(name = "validated_by")
    private UUID validatedBy;

    @Column(name = "validated_at", nullable = false)
    private Instant validatedAt;

    protected TicketValidation() {
    }

    public TicketValidation(Ticket ticket, Station station, GateDirection direction, GateResult result, String reason,
            UUID validatedBy, Instant validatedAt, Integer fareDifferenceVnd) {
        this.fareDifferenceVnd = fareDifferenceVnd;
        this.ticket = ticket;
        this.station = station;
        this.direction = direction;
        this.result = result;
        this.reason = reason;
        this.validatedBy = validatedBy;
        this.validatedAt = validatedAt;
    }

    public Long getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public Station getStation() { return station; }
    public GateDirection getDirection() { return direction; }
    public GateResult getResult() { return result; }
    public String getReason() { return reason; }
    public UUID getValidatedBy() { return validatedBy; }
    public Instant getValidatedAt() { return validatedAt; }
}
