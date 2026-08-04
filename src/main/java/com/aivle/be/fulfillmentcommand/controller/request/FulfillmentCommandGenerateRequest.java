package com.aivle.be.fulfillmentcommand.controller.request;

import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.CommandPolicyProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.SplittableRandom;

public record FulfillmentCommandGenerateRequest(
        @NotNull
        @Schema(
                description = "생성할 명령 종류. AUTO는 현재 실행 가능한 INBOUND/OUTBOUND/BOTH 중 무작위 선택",
                example = "AUTO"
        )
        FulfillmentCommandMode mode,

        @Min(0) @Max(50)
        @Schema(description = "생성할 입고 BOX 명령 수. null이면 빈 선반 범위에서 1~50 무작위", example = "2")
        Integer inboundCount,

        @Min(0) @Max(50)
        @Schema(description = "생성할 출고 BOX 명령 수. null이면 출고 가능 BOX 범위에서 1~50 무작위", example = "2")
        Integer outboundCount,

        @Size(max = 60)
        @Schema(description = "입고 대상 상품 코드 필터. 비어 있으면 전체 상품 중 무작위 선택")
        List<@Pattern(regexp = "ITEM-[0-9]{3}") String> inboundProductCodes,

        @Size(max = 60)
        @Schema(description = "출고 대상 상품 코드 필터. 비어 있으면 출고 가능한 전체 BOX 중 무작위 선택")
        List<@Pattern(regexp = "ITEM-[0-9]{3}") String> outboundProductCodes,

        @Pattern(regexp = "low|medium|high")
        @Schema(description = "plan 작업 우선순위", example = "medium")
        String priority,

        @Min(0) @Max(3_600_000)
        @Schema(description = "각 명령 사이의 발생 간격(ms)", example = "1000")
        Long releaseIntervalMs,

        @Schema(
                description = "AI 입력 표현 방식. 모든 모드에서 structuredInput은 실행 원본으로 유지됨",
                example = "STRUCTURED_WITH_POLICY"
        )
        CommandExpressionMode commandExpressionMode,

        @Schema(
                description = "자연어 정책 프로필. AUTO는 지원 정책 중 무작위 선택",
                example = "BALANCED"
        )
        CommandPolicyProfile policyProfile,

        @Schema(
                description = "구조화 입력을 기본으로 유지하면서 LLM 부가 정책 표현을 30% 비율로 혼합",
                example = "true"
        )
        Boolean mixStructuredWithPolicy,

        @Schema(
                description = "구조화 입력을 기본으로 유지하면서 전체 자연어 표현을 30% 확률로 적용",
                example = "false"
        )
        Boolean mixNaturalLanguage
) {
    public record ExpressionMix(int structuredOnly, int structuredWithPolicy, int naturalLanguage) {
        public ExpressionMix {
            if (structuredOnly < 0 || structuredWithPolicy < 0 || naturalLanguage < 0
                    || structuredOnly + structuredWithPolicy + naturalLanguage != 100) {
                throw new IllegalArgumentException("command expression mix must total 100");
            }
        }
    }

    public static FulfillmentCommandGenerateRequest automatic() {
        return new FulfillmentCommandGenerateRequest(
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
                false
        );
    }

    public int effectiveInboundCount() {
        return mode.includesInbound() && inboundCount != null ? inboundCount : 0;
    }

    public int effectiveOutboundCount() {
        return mode.includesOutbound() && outboundCount != null ? outboundCount : 0;
    }

    public String effectivePriority() {
        return priority == null || priority.isBlank() ? "medium" : priority;
    }

    public long effectiveReleaseIntervalMs() {
        return releaseIntervalMs == null ? 0L : releaseIntervalMs;
    }

    public FulfillmentCommandGenerateRequest resolve(
            FulfillmentCommandMode resolvedMode,
            int resolvedInboundCount,
            int resolvedOutboundCount
    ) {
        return new FulfillmentCommandGenerateRequest(
                resolvedMode,
                resolvedInboundCount,
                resolvedOutboundCount,
                inboundProductCodes,
                outboundProductCodes,
                priority,
                releaseIntervalMs,
                commandExpressionMode,
                policyProfile,
                mixStructuredWithPolicy,
                mixNaturalLanguage
        );
    }

    public CommandExpressionMode effectiveCommandExpressionMode() {
        return commandExpressionMode == null ? CommandExpressionMode.STRUCTURED_ONLY : commandExpressionMode;
    }

    public CommandPolicyProfile effectivePolicyProfile() {
        return policyProfile == null ? CommandPolicyProfile.AUTO : policyProfile;
    }

    public boolean hasExpressionMixConfiguration() {
        return mixStructuredWithPolicy != null || mixNaturalLanguage != null;
    }

    public ExpressionMix effectiveExpressionMix() {
        if (hasExpressionMixConfiguration()) {
            int policyRatio = Boolean.TRUE.equals(mixStructuredWithPolicy) ? 30 : 0;
            int naturalLanguageRatio = Boolean.TRUE.equals(mixNaturalLanguage) ? 30 : 0;
            return new ExpressionMix(
                    100 - policyRatio - naturalLanguageRatio,
                    policyRatio,
                    naturalLanguageRatio
            );
        }

        return switch (effectiveCommandExpressionMode()) {
            case AUTO -> new ExpressionMix(60, 30, 10);
            case STRUCTURED_ONLY -> new ExpressionMix(100, 0, 0);
            case STRUCTURED_WITH_POLICY -> new ExpressionMix(0, 100, 0);
            case NATURAL_LANGUAGE -> new ExpressionMix(0, 0, 100);
        };
    }

    /** Resolve one expression branch from the fresh random seed assigned to this batch. */
    public CommandExpressionMode selectCommandExpressionMode(long cycleSeed) {
        CommandExpressionMode requested = effectiveCommandExpressionMode();
        if (requested != CommandExpressionMode.AUTO) {
            return requested;
        }

        ExpressionMix mix = effectiveExpressionMix();
        int draw = new SplittableRandom(cycleSeed).nextInt(100);
        if (draw < mix.structuredWithPolicy()) {
            return CommandExpressionMode.STRUCTURED_WITH_POLICY;
        }
        if (draw < mix.structuredWithPolicy() + mix.naturalLanguage()) {
            return CommandExpressionMode.NATURAL_LANGUAGE;
        }
        return CommandExpressionMode.STRUCTURED_ONLY;
    }
}
