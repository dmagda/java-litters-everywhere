# How Java Apps Litter Beyond the Heap

As explained in [this article](https://dzone.com/articles/how-java-apps-litter-beyond-the-heap) a typical Java application leaves footprints not only in the Java Heap but also triggers garbage generation in a relational database and on SSDs.

This project includes a few exercises that let you witness this in practice.

<!-- vscode-markdown-toc -->

- [How Java Apps Litter Beyond the Heap](#how-java-apps-litter-beyond-the-heap)
  - [PostgreSQL - Records Versioning and Vacuum](#postgresql-records-versioning-and-vacuum)
    - [Start Postgres](#start-postgres)
    - [Start Application](#start-application)
    - [Enable Pageinspect Extension](#enable-pageinspect-extension)
    - [Put First Pizza Order](#put-first-pizza-order)
    - [Change Order Status](#change-order-status)
    - [Trigger Ordinary Vacuum](#trigger-ordinary-vacuum)
    - [Trigger Full Vacuum](#trigger-full-vacuum)
    - [Delete Order](#delete-order)
    - [Create Index for Order Time](#create-index-for-order-time)
    - [Update Order Time](#update-order-time)
    - [Update Column That is Not Indexed](#update-column-that-is-not-indexed)
    - [Trigger Vacuum](#trigger-vacuum)
- [YugabyteDB - Columns Versioning and Compaction](#yugabytedb-columns-versioning-and-compaction)
    - [Start YugabyteDB](#start-yugabytedb)
    - [Start Application](#start-application)
    - [Process Pizza Order](#process-pizza-order)

<!-- vscode-markdown-toc-config
    numbering=false
    autoSave=true
    /vscode-markdown-toc-config -->
<!-- /vscode-markdown-toc -->

##  PostgreSQL - Records Versioning and Vacuum

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

    ctid  | state  | xmin | xmin_age | xmax | hhu | hot | t_ctid 
    -------+--------+------+----------+------+-----+-----+--------
    (0,1) | normal | 1188 |        1 | 0 a  |     |     | (0,1)
    ```
        * `ctid` - physical location of a record within the page (page id and item id)
        * `state` - state of the record
        * `xmin` - transactions that insterted the version.
        * `xmax` - transaction that deleted the version.
        * `hhu` - heap hot update, the version is referenced from an index, traverse to the next version using ctid ref.
        * `hot` - heap only tuple, the version is created only in heap without the index update.
        * `t_ctid` - location of the latest version (traverse ctid)

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

     ctid  | state  |  xmin  | xmin_age |  xmax  | hhu | hot | t_ctid 
    -------+--------+--------+----------+--------+-----+-----+--------
    (0,1) | normal | 1102 c |        4 | 1103 c | t   |     | (0,2)
    (0,2) | normal | 1103 c |        3 | 1104 c | t   | t   | (0,3)
    (0,3) | normal | 1104 c |        2 | 1105   | t   | t   | (0,4)
    (0,4) | normal | 1105   |        1 | 0 a    |     | t   | (0,4)
    (4 rows)
    ```

### Trigger Ordinary Vacuum

The vacuum is a process that traverses through tables and indexes to garbage-collect old versions of the records. There are two types of VACUUM in Postgres - the ordinary one that removes the dead/old records freeing up the space for new data and the full vacuum that can defragments the space by moving all live records into space earlier in the file/memory.

1. Trigger the ordinary VACUUM manually:
    ```sql
    vacuum;
    ```
2. Make sure there are no record versions left for the first order and their space is available for new data:
    ```sql
    select * from heap_page('pizza_order',0,0);

     ctid  |     state     |  xmin  | xmin_age | xmax | hhu | hot | t_ctid 
    -------+---------------+--------+----------+------+-----+-----+--------
    (0,1) | redirect to 4 |        |          |      |     |     | 
    (0,2) | unused        |        |          |      |     |     | 
    (0,3) | unused        |        |          |      |     |     | 
    (0,4) | normal        | 1197 c |        1 | 0 a  |     | t   | (0,4)
    ```
3. Update the record's order time triggering the creation of a new version of the record:
    ```shell
    curl -i -X PUT \
        --url http://localhost:8080/changeOrderTime \
        --data 'id=1' \
        --data 'orderTime=2022-09-26 13:10:00' 
    ```
4. Make sure that the new version reused the space earlier in the page:
    ```sql
    select * from heap_page('pizza_order',0,0);

     ctid  |     state     |  xmin  | xmin_age | xmax | hhu | hot | t_ctid 
    -------+---------------+--------+----------+------+-----+-----+--------
    (0,1) | redirect to 4 |        |          |      |     |     | 
    (0,2) | normal        | 1198   |        1 | 0 a  |     | t   | (0,2)
    (0,3) | unused        |        |          |      |     |     | 
    (0,4) | normal        | 1197 c |        2 | 1198 | t   | t   | (0,2)
    ```
    In this example the `(0,2)` space is reused by the new version.

### Trigger Full Vacuum

The full vacuum can defragment the space in memory and on disk. This operation might take a while and block the execution of your app. It's comparable to the stop-the-world pause in Java.

1. Trigger the full VACUUM:
    ```sql
    vacuum full;
    ```
2. Make sure the old versions of the records are remove and all live records moved to the beginning of the page:
    ```sql
    select * from heap_page('pizza_order',0,0);

     ctid  | state  |  xmin  | xmin_age | xmax | hhu | hot | t_ctid 
    -------+--------+--------+----------+------+-----+-----+--------
    (0,1) | normal | 1198 f |       71 | 0 a  |     |     | (0,1)
    (1 row)
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

     ctid  | state  |  xmin  | xmin_age | xmax | hhu | hot | t_ctid 
    -------+--------+--------+----------+------+-----+-----+--------
    (0,1) | normal | 1198 f |       77 | 1269 |     |     | (0,1)
    ```
4. Trigger vacuum to confirm the space is fully reclaimed (because that was the only record left in the page):
    ```sql
    vacuum;

    select * from heap_page('pizza_order',0,0);

    ERROR:  block number 0 is out of range for relation "pizza_order"
    CONTEXT:  SQL function "heap_page" statement 1
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

Finally, execute the ordinary vacuum process manually and check the state of the index and table memory. Should look as follows:

1. Execute vacuum:
    ```sql
    vacuum;
    ```
2. Take a look at the table and index pages:
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
## YugabyteDB - Columns Versioning and Compaction

YugabyteDB is an LSM-tree based database. It uses another approach to keep track of different versions of the columns & records. Same as in Postgres, YugabyteDB stores multiple versions for the sake of MVCC.

### Start YugabyteDB

1. Start a single-node YugabyteDB instance:
    ```shell
    rm -r ~/yb_docker_data
    mkdir ~/yb_docker_data

    docker network create yugabytedb_network

    docker run -d --name yugabytedb_node1 --net yugabytedb_network \
    -p 7001:7000 -p 9000:9000 -p 5433:5433 \
    -v ~/yb_docker_data/node1:/home/yugabyte/yb_data --restart unless-stopped \
    yugabytedb/yugabyte:latest \
    bin/yugabyted start --listen=yugabytedb_node1 \
    --base_dir=/home/yugabyte/yb_data --daemon=false
    ```
2. Open a psql session with the instance:
    ```shell
    psql -h 127.0.0.1 -p 5433 yugabyte -U yugabyte -w
    ```

### Start Application

1. Open the `src\main\resources\application.properties` file and perform the following changes:
    ```yaml
    #Uncomment the YugabyteDB-specific connectivity settings:
    spring.datasource.url = jdbc:postgresql://127.0.0.1:5433/yugabyte
    spring.datasource.username = yugabyte
    spring.datasource.password = yugabyte

    #And disable to Postgres-specific settings
    # spring.datasource.url = jdbc:postgresql://localhost:5432/postgres
    # spring.datasource.username = postgres
    # spring.datasource.password = password
    ```
2. Start the app:
    ```shell
    mvn spring-boot:run
    ```
### Process Pizza Order

1. Add the first order through the app:
    ```shell
    curl -i -X POST \
        --url http://localhost:8080/putNewOrder \
        --data 'id=3' 
    ```
2. In your psql session, use a standard SQL request to confirm the record is in the database:
    ```sql
    select * from pizza_order;
    ```
3. Update the order status to `Baking`:
    ```shell
        curl -i -X PUT \
        --url http://localhost:8080/changeStatus \
        --data 'id=3' \
        --data 'status=Baking'
    ```
4. Update the order status one more time to `Delivering`:
    ```shell
        curl -i -X PUT \
        --url http://localhost:8080/changeStatus \
        --data 'id=3' \
        --data 'status=Delivering'
    ```

5. Connect to the YugabyteDB instance container:
    ```shell
    docker exec -it yugabytedb_node1 /bin/bash
    
    cd bin/
    ```
6. Find the `pizza_order` table ID:
    ```shell
    yb-admin -master_addresses yugabytedb_node1:7100 list_tables include_table_id | grep pizza_order
    ```
7. Manually flush the memtable to disk (thus, creating the SST file):
    ```shell
    yb-admin -master_addresses yugabytedb_node1:7100 flush_table ysql.yugabyte pizza_order
    ```
8. Look into the SST structure:
    ```shell
    ./sst_dump --command=scan --file=/home/yugabyte/yb_data/data/yb-data/tserver/data/rocksdb/table-{PIZZA_ORDER_TABLE_ID}/tablet-{TABLET_ID}/000010.sst --output_format=decoded_regulardb

    #The output might be as follows
    SubDocKey(DocKey(0xfca0, [3], []), [SystemColumnId(0); HT{ physical: 1663341345811041 }]) -> null; intent doc ht: HT{ physical: 1663341345791024 }
    SubDocKey(DocKey(0xfca0, [3], []), [ColumnId(1); HT{ physical: 1663341368800661 }]) -> 4629700416936886278; intent doc ht: HT{ physical: 1663341368790540 }
    SubDocKey(DocKey(0xfca0, [3], []), [ColumnId(1); HT{ physical: 1663341360382537 }]) -> 4611686018427404292; intent doc ht: HT{ physical: 1663341360371027 }
    SubDocKey(DocKey(0xfca0, [3], []), [ColumnId(1); HT{ physical: 1663341345811041 w: 1 }]) -> 4575657221408440322; intent doc ht: HT{ physical: 1663341345791024 w: 1 }
    SubDocKey(DocKey(0xfca0, [3], []), [ColumnId(2); HT{ physical: 1663341368800661 w: 1 }]) -> 716642145747000; intent doc ht: HT{ physical: 1663341368790540 w: 1 }
    SubDocKey(DocKey(0xfca0, [3], []), [ColumnId(2); HT{ physical: 1663341360382537 w: 1 }]) -> 716642145747000; intent doc ht: HT{ physical: 1663341360371027 w: 1 }
    SubDocKey(DocKey(0xfca0, [3], []), [ColumnId(2); HT{ physical: 1663341345811041 w: 2 }]) -> 716642145747000; intent doc ht: HT{ physical: 1663341345791024 w: 2 }
    ```
    
    Note, the output shows that YugabyteDB created new versions for `ColumnId(2)` which is `order_time`. Even though we didn't update the column, JPA/Hibernate generates
    the following SQL query if an Entity is updated via the `repository.save(...)` method.
    ```sql
    Hibernate: 
    update
        pizza_order 
    set
        order_time=?,
        status=? 
    where
        id=?
    ```