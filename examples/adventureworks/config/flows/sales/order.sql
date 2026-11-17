-- :name order
select salesorderid as order_id, orderdate, status, customerid, subtotal, taxamt, freight, totaldue
  from sales.salesorderheader
 where salesorderid in :order_id
