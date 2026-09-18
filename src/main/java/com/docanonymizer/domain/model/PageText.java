package com.docanonymizer.domain.model;

import java.util.List;

/** Texto crudo de una pagina, tal y como lo devolvio el extractor, partido en lineas. */
public record PageText(int number, List<String> lines) {

    public PageText {
        lines = List.copyOf(lines);
    }

    public String joined() {
        return String.join("\n", lines);
    }
}
