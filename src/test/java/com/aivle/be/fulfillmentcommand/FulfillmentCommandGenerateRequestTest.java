package com.aivle.be.fulfillmentcommand;

import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.CommandPolicyProfile;
import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FulfillmentCommandGenerateRequestTest {

    @Test
    void automaticCycleDefaultsToStructuredInputAndAutomaticPolicy() {
        FulfillmentCommandGenerateRequest request = FulfillmentCommandGenerateRequest.automatic();

        assertThat(request.effectiveCommandExpressionMode()).isEqualTo(CommandExpressionMode.AUTO);
        assertThat(request.effectivePolicyProfile()).isEqualTo(CommandPolicyProfile.AUTO);
        assertThat(request.effectiveExpressionMix())
                .isEqualTo(new FulfillmentCommandGenerateRequest.ExpressionMix(100, 0, 0));
    }

    @Test
    void missingNewOptionsRemainBackwardCompatible() {
        FulfillmentCommandGenerateRequest request = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.OUTBOUND,
                0,
                2,
                null,
                null,
                "medium",
                0L,
                null,
                null,
                null,
                null
        );

        assertThat(request.effectiveCommandExpressionMode())
                .isEqualTo(CommandExpressionMode.STRUCTURED_ONLY);
        assertThat(request.effectivePolicyProfile()).isEqualTo(CommandPolicyProfile.AUTO);
    }

    @Test
    void resolvingInboundOutboundModePreservesExpressionOptions() {
        FulfillmentCommandGenerateRequest request = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.AUTO,
                null,
                null,
                null,
                null,
                "high",
                1000L,
                CommandExpressionMode.NATURAL_LANGUAGE,
                CommandPolicyProfile.BATTERY_SAVING,
                null,
                null
        );

        FulfillmentCommandGenerateRequest resolved = request.resolve(FulfillmentCommandMode.BOTH, 3, 4);

        assertThat(resolved.commandExpressionMode()).isEqualTo(CommandExpressionMode.NATURAL_LANGUAGE);
        assertThat(resolved.policyProfile()).isEqualTo(CommandPolicyProfile.BATTERY_SAVING);
        assertThat(resolved.effectiveInboundCount()).isEqualTo(3);
        assertThat(resolved.effectiveOutboundCount()).isEqualTo(4);
    }

    @Test
    void expressionTogglesKeepStructuredInputAsTheBaseMix() {
        FulfillmentCommandGenerateRequest request = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.AUTO,
                null,
                null,
                null,
                null,
                "medium",
                0L,
                CommandExpressionMode.AUTO,
                CommandPolicyProfile.AUTO,
                true,
                false
        );

        assertThat(request.effectiveExpressionMix())
                .isEqualTo(new FulfillmentCommandGenerateRequest.ExpressionMix(70, 30, 0));
    }

    @Test
    void bothExpressionTogglesProduceFortyThirtyThirtyMix() {
        FulfillmentCommandGenerateRequest request = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.AUTO,
                null,
                null,
                null,
                null,
                "medium",
                0L,
                CommandExpressionMode.AUTO,
                CommandPolicyProfile.AUTO,
                true,
                true
        );

        assertThat(request.effectiveExpressionMix())
                .isEqualTo(new FulfillmentCommandGenerateRequest.ExpressionMix(40, 30, 30));
    }

    @Test
    void naturalLanguageToggleKeepsSeventyPercentStructuredInput() {
        FulfillmentCommandGenerateRequest request = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.AUTO,
                null,
                null,
                null,
                null,
                "medium",
                0L,
                CommandExpressionMode.AUTO,
                CommandPolicyProfile.AUTO,
                false,
                true
        );

        assertThat(request.effectiveExpressionMix())
                .isEqualTo(new FulfillmentCommandGenerateRequest.ExpressionMix(70, 0, 30));
    }

    @Test
    void expressionSelectionIsStableForTheSameCycleSeed() {
        FulfillmentCommandGenerateRequest request = FulfillmentCommandGenerateRequest.automatic();

        assertThat(request.selectCommandExpressionMode(2026080305L))
                .isEqualTo(request.selectCommandExpressionMode(2026080305L));
    }
}
