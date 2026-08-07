package com.aivle.be.boardpost.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BoardPostCreateRequest(
        @NotBlank(message = "제목은 비어 있을 수 없습니다.")
        @Size(max = 200, message = "제목은 200자 이하여야 합니다.")
        String title,

        @NotBlank(message = "내용은 비어 있을 수 없습니다.")
        @Size(max = 5000, message = "내용은 5000자 이하여야 합니다.")
        String content
) {
}
