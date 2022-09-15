package com.yugabytedb.example.java.data;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.yugabytedb.example.java.data.PizzaOrder.OrderStatus;

@Controller
public class PizzaOrdersController {
    @Autowired
    PizzaOrdersRepository repository;

    @PostMapping("/putNewOrder")
    public ResponseEntity<PizzaOrder> addNewOrder(@RequestParam("id") Integer id) {
        PizzaOrder order = new PizzaOrder();
        order.setId(id);
        order.setStatus(OrderStatus.Ordered);

        order = repository.save(order);

        return ResponseEntity.ok(order);
    }

    @PutMapping("/changeStatus")
    public ResponseEntity<PizzaOrder> changeStatus(@RequestParam("id") Integer id,
            @RequestParam("status") OrderStatus status) {

        Optional<PizzaOrder> orderOptional = repository.findById(id);
        if (orderOptional.isEmpty())
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        PizzaOrder order = orderOptional.get();
        order.setStatus(status);
        order = repository.save(order);

        return ResponseEntity.ok(order);
    }

    @PutMapping("/changeOrderTime")
    public ResponseEntity<PizzaOrder> updateOrderTime(@RequestParam("id") Integer id,
            @RequestParam("orderTime") Timestamp orderTime) {

        Optional<PizzaOrder> orderOptional = repository.findById(id);
        if (orderOptional.isEmpty())
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        PizzaOrder order = orderOptional.get();
        order.setOrderTime(orderTime);
        order = repository.save(order);

        return ResponseEntity.ok(order);
    }

    @DeleteMapping("/deleteOrder")
    public ResponseEntity<String> deleteOrder(@RequestParam("id") Integer id) {
        Optional<PizzaOrder> orderOptional = repository.findById(id);
        if (orderOptional.isEmpty())
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        repository.delete(orderOptional.get());

        return ResponseEntity.ok("Deleted the order");
    }

    @GetMapping("/allOrders")
    public ResponseEntity<List<PizzaOrder>> getAllOrders() {
        return ResponseEntity.ok(repository.findAll());
    }

    @GetMapping("/ping")
    public ResponseEntity<String> sayHi() {
        return ResponseEntity.ok("Feel hungry? Let's get a pizza baked for you!");
    }
}
