package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.data.SecurityQueueInfo;
import ir.ramtung.tinyme.domain.service.Controls.SecurityErrorControl;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.EnterOrderRepo;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.jgroups.util.Tuple;

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
    int lastTradePrice = 0;
    @Builder.Default
    private int openingPrice = 0;
    @Builder.Default
    @Setter
    private MatchingState state = MatchingState.CONTINUOUS;
    @Builder.Default
    private SecurityQueueInfo queueInfo = new SecurityQueueInfo();
    @Builder.Default
    private SecurityErrorControl errorControl = new SecurityErrorControl();

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) throws InvalidRequestException {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                        getOrderBook().totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        final Order order = makeOrder(enterOrderRq, broker, shareholder);
        errorControl.verifyNewOrder(order, this.isAuction());

        return handleEnterOrder(order, enterOrderRq.getRequestId(), matcher);
    }

    private Order makeOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder) {
        if (enterOrderRq.getPeakSize() == 0)
            return Order.builder()
                    .orderId(enterOrderRq.getOrderId())
                    .security(this)
                    .side(enterOrderRq.getSide())
                    .quantity(enterOrderRq.getQuantity())
                    .price(enterOrderRq.getPrice())
                    .broker(broker)
                    .shareholder(shareholder)
                    .entryTime(enterOrderRq.getEntryTime())
                    .status(OrderStatus.FirstEntry)
                    .minimumExecutionQuantity(enterOrderRq.getMinimumExecutionQuantity())
                    .stopPrice(enterOrderRq.getStopPrice()).build();
        else
            return IcebergOrder.builder()
                    .orderId(enterOrderRq.getOrderId())
                    .security(this)
                    .side(enterOrderRq.getSide())
                    .quantity(enterOrderRq.getQuantity())
                    .price(enterOrderRq.getPrice())
                    .broker(broker)
                    .shareholder(shareholder)
                    .entryTime(enterOrderRq.getEntryTime())
                    .peakSize(enterOrderRq.getPeakSize())
                    .status(OrderStatus.FirstEntry)
                    .minimumExecutionQuantity(enterOrderRq.getMinimumExecutionQuantity())
                    .build();
    }

    public MatchResult handleEnterOrder(Order order, long reqId, Matcher matcher){
        MatchResult result = matcher.execute(order, lastTradePrice, this.state);
        handleAcceptingState(result, order, reqId);
        return result;
    }

    private void handleAcceptingState(MatchResult result, Order order, long rqId){
        if(result.outcome() == MatchingOutcome.ACCEPTED){
            queueInfo.addToDisabled(order, rqId);
        }
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = queueInfo.findOrder(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        errorControl.verifyDelete(order, this.isAuction());
        handleCredit(order, order.getSide(), true);

        queueInfo.deleteOrder(order, deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = queueInfo.findOrder(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        errorControl.verifyUpdate(order, updateOrderRq);

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                        getOrderBook().totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        handleCredit(order, updateOrderRq.getSide(), true);
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            handleCredit(order, updateOrderRq.getSide(), false);
            return MatchResult.executed(null, List.of());
        }
        else
            order.markAsNew();

        getOrderBook().removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        MatchResult matchResult = matcher.execute(order, lastTradePrice, this.state);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            getOrderBook().enqueue(originalOrder);
            handleCredit(originalOrder, updateOrderRq.getSide(), false);
        }
        return matchResult;
    }

    private void handleCredit(Order order, Side side, boolean increase) {
        if (side == Side.BUY)
            if (increase)
                order.getBroker().increaseCreditBy(order.getValue());
            else
                order.getBroker().decreaseCreditBy(order.getValue());
    }

    public void handleDisabledOrders() {
        handleEachDisabled(queueInfo.getBuyDisabledOrders(), queueInfo.getBuyEnabledOrders(), true);
        handleEachDisabled(queueInfo.getSellDisabledOrders(), queueInfo.getSellEnabledOrders(), false);
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
            EnterOrderRepo toRemove = new EnterOrderRepo(true);
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
        queueInfo.deleteEnabledOrder(rqId, side);
    }

    public int getQuantityBasedOnPrice(int price) {
        return Math.min(getOrderBook().totalBuyQuantityByPrice(price), getOrderBook().totalSellQuantityByPrice(price));
    }

    private boolean isNewOneCloser(int newOne, int oldOne, int target){
        return(Math.abs(newOne - target) < Math.abs(oldOne - target));
    }

    private Tuple<Integer, Integer> calcOpeningPriceForEmptyQueue(){
        this.openingPrice = 0;
        return(new Tuple<>(0, 0));
    }
    public Tuple<Integer, Integer> calculateOpeningPrice(){
        if(getOrderBook().getBuyQueue().isEmpty() || getOrderBook().getSellQueue().isEmpty())
            return(calcOpeningPriceForEmptyQueue());

        Tuple<Integer, Integer> priceQuantity = new Tuple<>(
                this.lastTradePrice, getQuantityBasedOnPrice(this.lastTradePrice));
        int min = getOrderBook().getBuyQueue().getLast().getPrice();
        int max = getOrderBook().getSellQueue().getLast().getPrice();

        for (int cur = min; cur <= max; cur++) {
            int currentQuantity = getQuantityBasedOnPrice(cur);

            if (currentQuantity > priceQuantity.getVal2() ||
                    (currentQuantity == priceQuantity.getVal2()) && (isNewOneCloser(cur, priceQuantity.getVal1(), this.lastTradePrice)))
                priceQuantity = new Tuple<>(cur, currentQuantity);
        }

        this.openingPrice = priceQuantity.getVal1();
        return priceQuantity;
    }

    public MatchResult openAuction(Matcher matcher) {
        this.openingPrice = calculateOpeningPrice().getVal1();
        OrderBook candidateOrders = queueInfo.getCandidateOrders(this.openingPrice);
        OrderBook candidateOrdersCopy = candidateOrders.snapshot();

        MatchResult result = matcher.auctionMatch(candidateOrders, this.openingPrice);
        queueInfo.syncRemovedOrders(candidateOrders, candidateOrdersCopy, Side.BUY);
        queueInfo.syncRemovedOrders(candidateOrders, candidateOrdersCopy, Side.SELL);

        result.trades().forEach(Trade::payBuyerDebt);
        updateLastTradePrice(this.openingPrice);
        handleDisabledOrders();

        return result;
    }

    public void updateDisabledOrders(EnterOrderRq updateOrderRq){
        EnterOrderRepo orders = queueInfo.getDisabledOrders(updateOrderRq.getSide());
        Order order = orders.findByOrderId(updateOrderRq.getOrderId());
        long prevRqId = orders.getRqIdByOrderId(order.getOrderId());
        orders.addOrder(order, updateOrderRq.getRequestId());
        orders.removeByRqId(prevRqId);
    }

    public boolean isAuction() { return this.state == MatchingState.AUCTION; }

    public void transportEnabled(Side side){
        EnterOrderRepo orders = queueInfo.getEnabledOrders(side);;
        for(long rqId : orders.allOrderKeysSortedByStopPrice()){
            Order order = orders.findByRqId(rqId);
            order.setStopPriceZero();
            getOrderBook().putBack(order);
        }
        orders.clear();
    }

    public OrderBook getOrderBook() {
        return queueInfo.getOrderBook();
    }
}
