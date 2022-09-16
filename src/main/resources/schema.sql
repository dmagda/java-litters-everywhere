CREATE TYPE order_status AS ENUM('Ordered', 'Baking', 
    'Delivering', 'YummyInMyTummy');

CREATE TABLE pizza_order (
   id int PRIMARY KEY,
   status order_status NOT NULL,
   order_time timestamp NOT NULL DEFAULT now()
 );