package com.sap.cap.bookstore.handlers;

import cds.gen.ordersservice.*;
import cds.gen.sap.capire.bookstore.Books;
import cds.gen.sap.capire.bookstore.Books_;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.ql.cqn.CqnUpdate;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.persistence.PersistenceService;

import com.sap.cds.services.handler.annotations.ServiceName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@ServiceName(OrdersService_.CDS_NAME)
public class OrderService implements EventHandler {

    @Autowired
    PersistenceService db;

    @Before(event = CqnService.EVENT_CREATE, entity = OrderItems_.CDS_NAME)
    public void validateBookAndDecreaseStock(List<OrderItems> items) {
        for (OrderItems item : items) {
            String bookId = item.getBookId();
            Integer amount = item.getAmount();

            CqnSelect sel = Select.from(Books_.class).columns(Books_::stock)
                    .where(b -> b.ID().eq(bookId));

            Books book = db.run(sel).first(Books.class)
                    .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Book does not exist"));

            int stock = book.getStock();
            if (stock < amount) {
                throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Not enough books on stock");
            }

            book.setStock(stock - amount);
            CqnUpdate update = Update.entity(Books_.class).data(book).where(b -> b.ID().eq(bookId));
            db.run(update);
        }
    }

    @Before(event = CqnService.EVENT_CREATE, entity = Orders_.CDS_NAME)
    public void validateBookAndDecreaseStockViaOrders(List<Orders> orders) {
        for (Orders order : orders) {
            if (order.getItems() != null) {
                validateBookAndDecreaseStock(order.getItems());
            }
        }
    }

    @After(event = { CqnService.EVENT_READ, CqnService.EVENT_CREATE}, entity = OrderItems_.CDS_NAME)
    public void calculateNetAmount(List<OrderItems> items) {
        for (OrderItems item : items) {
            String bookId = item.getBookId();

            // get the book that was ordered

            CqnSelect sel = Select.from(Books_.class).where(b -> b.ID().eq(bookId));
            Books book = db.run(sel).single(Books.class);

            // calculate and set net amount
            item.setNetAmount(book.getPrice().multiply(new BigDecimal(item.getAmount())));
        }

    }

    @After(event = { CqnService.EVENT_READ, CqnService.EVENT_CREATE }, entity = Orders_.CDS_NAME)
    public void calculateTotal(List<Orders> orders) {
        for(Orders order : orders) {
            if(order.getItems() != null) {
                calculateNetAmount(order.getItems());
            }

            CqnSelect selectItems = Select.from(OrderItems_.class).where(i -> i.parent().ID().eq(order.getId()));
            List<OrderItems> items = db.run(selectItems).listOf(OrderItems.class);

            calculateNetAmount(items);


            // calculate and set the orders total
            BigDecimal total = new BigDecimal(0);
            for(OrderItems item : items) {
                total = total.add(item.getNetAmount());
            }
            order.setTotal(total);
        }
    }

}
