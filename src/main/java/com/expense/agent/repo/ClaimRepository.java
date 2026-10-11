package com.expense.agent.repo;

import com.expense.agent.domain.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    List<Claim> findByEmployeeIdOrderByCreatedAtDesc(String employeeId);
}
