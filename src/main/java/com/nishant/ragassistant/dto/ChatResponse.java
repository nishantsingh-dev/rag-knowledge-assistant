package com.nishant.ragassistant.dto;

import java.util.List;

public record ChatResponse(
        String answer,
        List<Source> sources,
        boolean fromCache   // will actually be used once Redis semantic caching is added (Weekend 3)
) {
    public record Source(String documentId, String filename, String snippet) {}
}
