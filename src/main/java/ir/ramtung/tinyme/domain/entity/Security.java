package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.repository.EnterOrderRepo;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    private final static boolean ASCENDING = true;
    private final static boolean DESCENDING = false;

    @Builder.Default
    EnterOrderRepo buyDisabledOrders = new EnterOrderRepo(ASCENDING);

    @Builder.Default
    EnterOrderRepo buyEnabledOrders = new EnterOrderRepo(ASCENDING);

    @Builder.Default
    EnterOrderRepo sellDisabledOrders = new EnterOrderRepo(DESCENDING);
    @Builder.Default
    EnterOrderRepo sellEnabledOrders = new EnterOrderRepo(DESCENDING);

    @Builder.Default
    int lastTradePrice = 0;

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        Order order;
        if (enterOrderRq.getPeakSize() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker,
                    shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW,
                    enterOrderRq.getMinimumExecutionQuantity(), enterOrderRq.getStopPrice());
        else
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), OrderStatus.NEW,
                    enterOrderRq.getMinimumExecutionQuantity());

        return handleEnterOrder(order, enterOrderRq.getRequestId(), matcher);
    }

    public MatchResult handleEnterOrder(Order order, long reqId, Matcher matcher){
        MatchResult result = matcher.execute(order, lastTradePrice);
        handleAcceptingState(result, order, reqId);
        return (result);
    }

    private void handleAcceptingState(MatchResult result, Order order, long rqId){
        if(result.outcome() == MatchingOutcome.ACCEPTED){
            if(order.getSide() == Side.BUY)
                buyDisabledOrders.addOrder(order, rqId);
            else
                sellDisabledOrders.addOrder(order, rqId);
        }
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = findOrder(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());

        if (orderBook.hasByOrderId(order.getSide(), order.getOrderId()))
            orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        else if (buyDisabledOrders.existByOrderId(deleteOrderRq.getOrderId()))
            buyDisabledOrders.removeByOrderId(deleteOrderRq.getOrderId());
        else if (sellDisabledOrders.existByOrderId(deleteOrderRq.getOrderId()))
            sellDisabledOrders.removeByOrderId(deleteOrderRq.getOrderId());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = findOrder(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        if ((order instanceof IcebergOrder) && !updateOrderRq.isIcebergOrderRq())
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.isIcebergOrderRq())
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.getMinimumExecutionQuantity() != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY);
        if (!order.isStopLimitOrder() && updateOrderRq.isStopLimitOrderRq())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_STOP_PRICE_FOR_ACTIVATED);

        if (order.isStopLimitOrder()) {
            if (updateOrderRq.isIcebergOrderRq())
                throw new InvalidRequestException(Message.STOP_ORDER_CANNOT_BE_ICEBERG_TOO);
            if (updateOrderRq.hasMinimumExecutionQuantity())
                throw new InvalidRequestException(Message.STOP_ORDER_CANNOT_HAVE_MINIMUM_EXEC_QUANTITY);
            if (!order.isUpdatingStopOrderPossible(updateOrderRq.getOrderId(), updateOrderRq.getSecurityIsin(), updateOrderRq.getBrokerId(), updateOrderRq.getSide(), updateOrderRq.getShareholderId()))
                throw new InvalidRequestException(Message.CANNOT_CHANGE_NOT_ALLOWED_PARAMETERS_BEFORE_ACTIVATION);
        }
        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        }
        else
            order.markAsNew();

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        MatchResult matchResult = matcher.execute(order, lastTradePrice);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }
    public void handleDisabledOrders(){
       handleEachDisabled(buyDisabledOrders, buyEnabledOrders, true);
       handleEachDisabled(sellDisabledOrders, sellEnabledOrders, false);
    }

    private boolean isActivationReady(boolean isBuySide, Order disabled){
        return (
                (isBuySide &&
                disabled.getStopPrice() <= lastTradePrice)
                ||
                (!isBuySide &&
                disabled.getStopPrice() >= lastTradePrice)
        );
    }

    private void enableTheRq(Order disabled, EnterOrderRepo toRemove,
                             EnterOrderRepo enabledRqs, long disabledKey){
        toRemove.addOrder(disabled, disabledKey);
        if (!enabledRqs.existByRqId(disabled.getOrderId()))
            enabledRqs.addOrder(disabled, disabledKey);
    }

    private void handleEachDisabled(EnterOrderRepo disabledRqs,
                                    EnterOrderRepo enabledRqs, boolean isBuySide){
        if(disabledRqs != null) {
            EnterOrderRepo toRemove = new EnterOrderRepo(ASCENDING);
            for (long disabledKey : disabledRqs.allOrderKeysSortedByStopPrice()) {
                if (isActivationReady(isBuySide, disabledRqs.findByRqId(disabledKey)))
                    enableTheRq(disabledRqs.findByRqId(disabledKey),
                            toRemove, enabledRqs, disabledKey);
            }
            for(long removeKey : toRemove.allOrderKeysSortedByStopPrice())
                disabledRqs.removeByRqId(removeKey);
        }
    }
    public void updateLastTradePrice(int lastTradePrice){
        this.lastTradePrice = lastTradePrice;
    }

    public void removeEnabledOrder(long rqId, Side side){
        if(side == Side.BUY)
            buyEnabledOrders.removeByRqId(rqId);
        else
            sellEnabledOrders.removeByRqId(rqId);
    }

    private Order findOrder(Side side, long orderId) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(side, orderId);
        if (order != null)
            return order;

        order = buyDisabledOrders.findByOrderId(orderId);
        if (order != null)
            return order;

        order = sellDisabledOrders.findByOrderId(orderId);
        if (order != null)
            return order;

        throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
    }
}
