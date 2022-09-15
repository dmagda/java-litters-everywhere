package com.yugabytedb.example.java.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PizzaOrdersRepository extends JpaRepository<PizzaOrder, Integer> {

}
