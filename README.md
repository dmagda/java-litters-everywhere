# How Java Apps Litter Beyond the Heap

As explained in [this article](https://dzone.com/articles/how-java-apps-litter-beyond-the-heap) a typical Java application leaves footprints not only in the Java Heap but also generates garbage in a relational database and SSDs.

This project includes a few exercises that let you witness this in practice.

<!-- vscode-markdown-toc -->

- [How Java Apps Litter Beyond the Heap](#how-java-apps-litter-beyond-the-heap)
  - [How Java Generates Garbage in PostgreSQL](#how-java-generates-garbage-in-postgresql)
    - [Start Postgres](#start-postgres)
    - [Start Application](#start-application)
    - [Enable Pageinspect Extension](#enable-pageinspect-extension)
    - [Put First Pizza Order](#put-first-pizza-order)
    - [Change Order Status](#change-order-status)
    - [Delete Order](#delete-order)
    - [Trigger Vacuum](#trigger-vacuum)
    - [Create Index for Order Time](#create-index-for-order-time)
    - [Update Order Time](#update-order-time)
    - [Update Column That is Not Indexed](#update-column-that-is-not-indexed)
    - [Trigger Vacuum](#trigger-vacuum-1)


<!-- vscode-markdown-toc-config
    numbering=false
    autoSave=true
    /vscode-markdown-toc-config -->
<!-- /vscode-markdown-toc -->

## How Java Generates Garbage in PostgreSQL

This project includes a standard Spring Boot Java application that works with Postgres. While the application executes, it will insert, update and delete records in the database. This is what every app usually does. However, in Postgres, an update and delete don't delete an existing record right away... Let's explore what happens in reality.

### Start Postgres

1. Launch a Postgres instance in a Docker container:
    ```shell
    mkdir ~/postgresql_data/

    docker run --name postgresql --net geo-messenger-net \
        -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=password \
        -p 5432:5432 \
        -v ~/postgresql_data/:/var/lib/postgresql/data -d postgres:13.8
    ```
2. Connect with psql to make sure the database is running:
    ```shell
    psql -h 127.0.0.1 --username=postgres
    ```
    Use `password` as the password

### Start Application

Launch an application that creates the `pizza_orders` table for our future experiments.

1. Run the app:
    ```shell
    mvn spring-boot:run
    ```
2. Open a terminal window and hit the following REST endpoint to confirm the app is running normally:
    ```shell
    curl -i -X GET http://localhost:8080/ping
    ```
3. Open your psql session and confirm the `pizza_orders` table is created:
    ```sql
    \d+ pizza_order
    ```

### Enable Pageinspect Extension

1. In your psql session, enable the `pageinspect` extension for Postgres:
    ```sql
    CREATE EXTENSION pageinspect;
    ```
2. Try to get the content of the first page of the `pizza_orders` table:
    ```sql
    select * from heap_page_items(get_raw_page('pizza_order',0));

    # You will get the exception below because there is no data in the table yet
    ERROR:  block number 0 is out of range for relation "pizza_order"
    ```
3. Create a helper function to simplify the future analysis:
    ```sql
    create function heap_page(relname text, pageno_from integer, pageno_to integer)
    returns table (ctid tid, state text, xmin text, xmin_age integer, xmax text,
    hhu text, /* heap hot update - the version is referenced from an index, traverse to the next version using ctid ref */
    hot text, /* heap only tuple - the version is created only in heap without the index update */
    t_ctid tid
    )
    AS $$
    select (pageno,lp)::text::tid as ctid,
        case lp_flags
        when 0 then 'unused'
        when 1 then 'normal'
        when 2 then 'redirect to '||lp_off
        when 3 then 'dead'
        end as state,
        t_xmin || case
        when (t_infomask & 256+512) = 256+512 then ' f'
        when (t_infomask & 256) > 0 then ' c'
        when (t_infomask & 512) > 0 then ' a'
        else ''
        end as xmin,
        age(t_xmin) as xmin_age,
        t_xmax || case
        when (t_infomask & 1024) > 0 then ' c'
        when (t_infomask & 2048) > 0 then ' a'
        else ''
        end as xmax,
        case when (t_infomask2 & 16384) > 0 then 't' end as hhu,
        case when (t_infomask2 & 32768) > 0 then 't' end as hot,
        t_ctid
    from generate_series(pageno_from, pageno_to) p(pageno),
        heap_page_items(get_raw_page(relname,pageno))
    order by pageno, lp;
    $$ language sql;
    ```
4. Confrim the new function is created succesfully:
    ```sql
    select * from heap_page('pizza_order',0,0);
    ```

### Put First Pizza Order

1. Add the first order through the app:
    ```shell
    curl -i -X POST \
        --url http://localhost:8080/putNewOrder \
        --data 'id=1' 
    ```
2. In your psql session, use a standard SQL request to confirm the record is in the database:
    ```sql
    select * from pizza_order;
    ```
3. Take a look at the page content:
    ```sql
    select * from heap_page('pizza_order',0,0);

    #The output might be as follows
     ctid  | state  |  xmin  | xmin_age | xmax | hhu | hot | t_ctid 
    -------+--------+--------+----------+------+-----+-----+--------
    (0,1) | normal | 1074 c |        1 | 0 a  |     |     | (0,1)
    ```
    TBD, explain every column
        * `hhu` - heap hot update, the version is referenced from an index, traverse to the next version using ctid ref.
        * `hot` - heap only tuple, the version is created only in heap without the index update.

### Change Order Status

An update operation in Postgres doesn't change the data in-place. Instead, it works like a delete+insert: the current version is labeled as deleted and the new one is inserted.

1. Change the order status to `Baking`:
    ```shell
    curl -i -X PUT \
        --url http://localhost:8080/changeStatus \
        --data 'id=1' \
        --data 'status=Baking'
    ```

2. Confirm there are two versions of the record in the table:
    ```sql
    select * from heap_page('pizza_order',0,0);
    ```
3. Change the status to `Delivering`:
    ```shell
    curl -i -X PUT \
        --url http://localhost:8080/changeStatus \
        --data 'id=1' \
        --data 'status=Delivering'
    ```
4. Confirm there are 3 versions now:
    ```sql
    select * from heap_page('pizza_order',0,0);
    ```
5. Finally, change the status to `YummyInMyTummy`:
    ```shell
    curl -i -X PUT \
        --url http://localhost:8080/changeStatus \
        --data 'id=1' \
        --data 'status=YummyInMyTummy'
    ```
6. Make sure the `pizza_orders` table now stores 4 versions of the same record:
    ```sql
    select * from heap_page('pizza_order',0,0);
    ```
### Delete Order

When you delete a record in Postgres, it's not remove from the table right away. Instead, it's labeled as deleted and will be garbage collected later.

1. Delete the first order:
    ```shell
    curl -i -X DELETE \
        --url http://localhost:8080/deleteOrder \
        --data 'id=1'
    ```
2. Confirm the order is no longer visible to the application:
    ```sql
    select * from pizza_order;
    ```
3. But you'll see that all of the version of this just-delete order are still in the table:
    ```sql
    select * from heap_page('pizza_order',0,0);
    ```

### Trigger Vacuum

The vacuum is a process that traverses through tables and indexes to garbage-collect old versions of the records.

1. Trigger VACUUM manually:
    ```sql
    vacuum;
    ```
2. Make sure there are no record versions left for the first order:
    ```sql
    select * from heap_page('pizza_order',0,0);
    ```

### Create Index for Order Time

Postgres doesn't update an index page in-place as well. However, there are certain cases when a new version of an index is not created.

1. Create an index for the `order_time` column:
    ```sql
    CREATE INDEX pizza_order_time_idx ON pizza_order(order_time);
    ```

2. Put the second order in the queue:
    ```shell
    curl -i -X POST \
        --url http://localhost:8080/putNewOrder \
        --data 'id=2' 
    ```

3. Look at the index page (there is an index record that points to the record in the table):
    ```sql
    select itemoffset,ctid,htid,dead from bt_page_items('pizza_order_time_idx',1);

    itemoffset | ctid  | htid  | dead 
    ------------+-------+-------+------
          1 | (0,1) | (0,1) | f
    ```
    TODO explain the columns

4. Check the table page with the order:
    ```sql
    select * from heap_page('pizza_order',0,0);

     ctid  | state  |  xmin  | xmin_age | xmax | hhu | hot | t_ctid 
    -------+--------+--------+----------+------+-----+-----+--------
    (0,1) | normal | 1097 c |        1 | 0 a  |     |     | (0,1)
    ```

### Update Order Time

Now, update the order time for the second order and see what happens.

1. Update the order time:
    ```shell
    curl -i -X PUT \
        --url http://localhost:8080/changeOrderTime \
        --data 'id=2' \
        --data 'orderTime=2022-09-28 18:10:00' 
    ```    
2. Confirm the time is updated and that now you have two versions of the record internally:
    ```sql
    select * from pizza_order;

    id | status  |     order_time      
    ----+---------+---------------------
    2 | Ordered | 2022-09-28 18:10:00

    select * from heap_page('pizza_order',0,0);

    ctid  | state  |  xmin  | xmin_age |  xmax  | hhu | hot | t_ctid 
    -------+--------+--------+----------+--------+-----+-----+--------
    (0,1) | normal | 1097 c |        2 | 1098 c |     |     | (0,2)
    (0,2) | normal | 1098 c |        1 | 0 a    |     |     | (0,2)
    ```
3. Check the index to confirm you have two index records pointing to those two different versions in the table:
    ```sql
    select itemoffset,ctid,htid,dead from bt_page_items('pizza_order_time_idx',1);
    
    itemoffset | ctid  | htid  | dead 
    ------------+-------+-------+------
            1 | (0,1) | (0,1) | f
            2 | (0,2) | (0,2) | f
    ```

### Update Column That is Not Indexed

However, when you update a non-indexed column then Postgres won't create an additional version in the index.

1. Change the second order's status:
    ```shell
        curl -i -X PUT \
        --url http://localhost:8080/changeStatus \
        --data 'id=2' \
        --data 'status=Baking'
    ```
2. The new version of the record is added to the table:
    ```shell
    select * from heap_page('pizza_order',0,0);

    ctid  | state  |  xmin  | xmin_age |  xmax  | hhu | hot | t_ctid 
    -------+--------+--------+----------+--------+-----+-----+--------
    (0,1) | normal | 1097 c |        3 | 1098 c |     |     | (0,2)
    (0,2) | normal | 1098 c |        2 | 1099   | t   |     | (0,3)
    (0,3) | normal | 1099   |        1 | 0 a    |     | t   | (0,3)
    (3 rows)
    ```
3. But not to the index:
    ```shell
    select itemoffset,ctid,htid,dead from bt_page_items('pizza_order_time_idx',1);

    itemoffset | ctid  | htid  | dead 
    ------------+-------+-------+------
            1 | (0,1) | (0,1) | f
            2 | (0,2) | (0,2) | f
    ```
    * The index references the latest version of the record `ctid = (0,2)`
    * That version of the record has the `hhu` (heap hot update) flag set to `true` meaning that this version was updated only in the table and you need to traverse to `t_ctid = (0,3)` for the next version of the record
    * Tht version `ctid=(0,3)` has the `hot` (heap only tuple) set to `true` meaning the version was created only in the table and not referenced directly from the index.

### Trigger Vacuum

Finally, execute the vacuum process manually and check the state of the index and table memory. Should look as follows:

```sql
select itemoffset,ctid,htid,dead from bt_page_items('pizza_order_time_idx',1);
 itemoffset | ctid  | htid  | dead 
------------+-------+-------+------
          1 | (0,2) | (0,2) | f
(1 row)

select * from heap_page('pizza_order',0,0);
 ctid  |     state     |  xmin  | xmin_age | xmax | hhu | hot | t_ctid 
-------+---------------+--------+----------+------+-----+-----+--------
 (0,1) | unused        |        |          |      |     |     | 
 (0,2) | redirect to 3 |        |          |      |     |     | 
 (0,3) | normal        | 1099 c |        1 | 0 a  |     | t   | (0,3)
(3 rows)
```
