package com.metroticket_api.student;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentVerificationImageRepository extends JpaRepository<StudentVerificationImage, UUID> {
}
