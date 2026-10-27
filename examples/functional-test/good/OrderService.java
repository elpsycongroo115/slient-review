package demo;

import java.util.Objects;

public final class OrderService {
    public void placeOrder(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
    }
}
