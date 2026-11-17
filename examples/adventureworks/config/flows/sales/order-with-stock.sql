-- :name order
select salesorderid as order_id, orderdate, status, customerid, totaldue
  from sales.salesorderheader
 where salesorderid = :order_id

-- :name lines
select d.salesorderid as order_id, d.salesorderdetailid as line_id, d.productid,
       p.name as product, d.orderqty, d.unitprice, d.unitpricediscount
  from sales.salesorderdetail d
  join production.product p on p.productid = d.productid
 where d.salesorderid = :order_id
 order by d.salesorderdetailid
