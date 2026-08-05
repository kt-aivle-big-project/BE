package com.aivle.be.fulfillmentcommand.domain;

public enum FulfillmentCommandMode {
    AUTO,
    INBOUND,
    OUTBOUND,
    BOTH;

    public boolean includesInbound() {
        return this == AUTO || this == INBOUND || this == BOTH;
    }

    public boolean includesOutbound() {
        return this == AUTO || this == OUTBOUND || this == BOTH;
    }
}
