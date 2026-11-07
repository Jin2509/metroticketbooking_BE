package com.metroticket_api.student;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentPolicyRepository extends JpaRepository<StudentPolicy, Short> {

    default StudentPolicy current() {
        return findById((short) 1).orElseGet(StudentPolicy::new);
    }
}
