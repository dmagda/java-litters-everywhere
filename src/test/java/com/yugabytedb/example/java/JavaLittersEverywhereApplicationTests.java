package com.yugabytedb.example.java;

import java.util.Optional;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.yugabytedb.example.java.data.PizzaOrder;
import com.yugabytedb.example.java.data.PizzaOrdersRepository;
import com.yugabytedb.example.java.data.PizzaOrder.OrderStatus;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JavaLittersEverywhereApplicationTests {

    @Autowired
    PizzaOrdersRepository repository;

    @Test
    @Order(1)
    void contextLoads() {
    }

    @Test
    @Order(2)
    void putNewOrder() {
        PizzaOrder pizzaOrder = new PizzaOrder();
        pizzaOrder.setId(1);
        pizzaOrder.setStatus(OrderStatus.Ordered);

        repository.save(pizzaOrder);
    }

    @Test
    @Order(3)
    void getPizzaOrder() {
        Optional<PizzaOrder> orderOptional = repository.findById(1);

        assert orderOptional.isPresent();

        PizzaOrder pizzaOrder = orderOptional.get();

        System.out.println(pizzaOrder);
    }
    
    @Test
    @Order(4)
    void updateOrderStatus() {
        Optional<PizzaOrder> orderOptional = repository.findById(1);

        assert orderOptional.isPresent();

        PizzaOrder pizzaOrder = orderOptional.get();
        
        
        pizzaOrder.setStatus(OrderStatus.Baking);
        
        repository.save(pizzaOrder);
    }

    @Test
    @Order(5)
    void deletePizzaOrder() {
        repository.delete(repository.getReferenceById(1));

        assert repository.findById(1).isEmpty();
    }
}
