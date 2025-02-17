package ir.ramtung.tinyme.messaging;

public class Message {
    public static final String INVALID_ORDER_ID = "Invalid order ID";
    public static final String ORDER_QUANTITY_NOT_POSITIVE = "Order quantity is not-positive";
    public static final String ORDER_PRICE_NOT_POSITIVE = "Order price is not-positive";
    public static final String UNKNOWN_SECURITY_ISIN = "Unknown security ISIN";
    public static final String ORDER_ID_NOT_FOUND = "Order ID not found in the order book";
    public static final String INVALID_PEAK_SIZE = "Iceberg order peak size is out of range";
    public static final String CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER = "Cannot specify peak size for a non-iceberg order";
    public static final String UNKNOWN_BROKER_ID = "Unknown broker ID";
    public static final String UNKNOWN_SHAREHOLDER_ID = "Unknown shareholder ID";
    public static final String BUYER_HAS_NOT_ENOUGH_CREDIT = "Buyer has not enough credit";
    public static final String QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE = "Quantity is not a multiple of security lot size";
    public static final String PRICE_NOT_MULTIPLE_OF_TICK_SIZE = "Price is not a multiple of security tick size";
    public static final String SELLER_HAS_NOT_ENOUGH_POSITIONS = "Seller has not enough positions";
    public static final String ORDER_MINIMUM_EXEC_QUANTITY_NEGATIVE = "Order minimum execution quantity is negative";
    public static final String ORDER_MINIMUM_EXEC_QUANTITY_BIGGER_THAN_QUANTITY = "Order minimum execution quantity is bigger than quantity";
    public static final String CANNOT_CHANGE_MINIMUM_EXEC_QUANTITY = "Cannot change minimum execution quantity in update request";
    public static final String ORDER_MINIMUM_EXEC_QUANTITY_NOT_SATISFY = "Order minimum execution quantity isn't satisfied";
    public static final String STOP_ORDER_CANNOT_HAVE_MINIMUM_EXEC_QUANTITY = "Stop limit order cannot have minimum execution quantity";
    public static final String STOP_ORDER_CANNOT_BE_ICEBERG_TOO = "Stop limit order cannot be iceberg simultaneously";
    public static final String STOP_PRICE_CANNOT_BE_NEGATIVE = "Stop price cannot be negative";
    public static final String CANNOT_CHANGE_STOP_PRICE_FOR_ACTIVATED = "Stop price of activated orders can't change";
    public static final String CANNOT_CHANGE_NOT_ALLOWED_PARAMETERS_BEFORE_ACTIVATION = "One or more parameters cannot be changed before activation";
    public static final String CANNOT_ADD_STOP_ORDER_IN_AUCTION_STATE = "Stop limit order cannot be added when security is in auction state";
    public static final String CANNOT_UPDATE_STOP_ORDER_IN_AUCTION_STATE = "Stop limit order cannot be updated when security is in auction state";
    public static final String CANNOT_DELETE_STOP_ORDER_IN_AUCTION_STATE = "Stop limit order cannot be deleted when security is in auction state";
    public static final String CANNOT_HAVE_MINIMUM_EXEC_QUANTITY_IN_AUCTION_STATE = "Cannot have minimum execution quantity when security is in auction state";
}
