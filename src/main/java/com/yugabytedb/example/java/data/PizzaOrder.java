package com.yugabytedb.example.java.data;

import java.sql.Timestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

@Entity
@TypeDef(name = "order_status", typeClass = PostgreSQLEnumType.class)
public class PizzaOrder {
    @Id
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "post_status_info")
    @Type(type = "order_status")
    private OrderStatus status;

    @CreationTimestamp
    private Timestamp orderTime;

    public enum OrderStatus {
        Ordered,
        Baking,
        Delivering,
        YummyInMyTummy
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Timestamp getOrderTime() {
        return orderTime;
    }

    public void setOrderTime(Timestamp orderTime) {
        this.orderTime = orderTime;
    }
}
