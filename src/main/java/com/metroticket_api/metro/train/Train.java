package com.metroticket_api.metro.train;

import java.time.Instant;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;

@Entity
@Table(name = "train")
public class Train {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name_train", length = 100)
    private String nameTrain;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id")
    private MetroLine line;

    @Column
    private Integer capacity;

    @Column(name = "manufacture_year")
    private Integer manufactureYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TrainStatus status = TrainStatus.READY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Train() {
    }

    public Train(String code, String nameTrain, MetroLine line, Integer capacity, Integer manufactureYear, TrainStatus status) {
        this.code = code;
        this.nameTrain = nameTrain;
        this.line = line;
        this.capacity = capacity;
        this.manufactureYear = manufactureYear;
        this.status = status == null ? TrainStatus.READY : status;
    }

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getNameTrain() {
        return nameTrain;
    }

    public void setNameTrain(String nameTrain) {
        this.nameTrain = nameTrain;
    }

    public MetroLine getLine() {
        return line;
    }

    public void setLine(MetroLine line) {
        this.line = line;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getManufactureYear() {
        return manufactureYear;
    }

    public void setManufactureYear(Integer manufactureYear) {
        this.manufactureYear = manufactureYear;
    }

    public TrainStatus getStatus() {
        return status;
    }

    public void setStatus(TrainStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
