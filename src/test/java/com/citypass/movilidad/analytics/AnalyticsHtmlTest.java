package com.citypass.movilidad.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsHtmlTest {

    @Test
    void rechazaHtmlNuloOVacioDeDocumentoHtml() {
        assertThat(AnalyticsHtml.isolate(null, null)).isNull();
        assertThat(AnalyticsHtml.isolate("texto plano", null)).isNull();
    }

    @Test
    void insertaPoliticasDespuesDelDoctypeYEscapaLaPoliticaRemota() {
        String isolated = AnalyticsHtml.isolate(
                "  <!DOCTYPE HTML><html><body>Tablero</body></html>",
                "default-src 'self' & data:");

        assertThat(isolated).startsWith("<!doctype html><meta http-equiv=\"Content-Security-Policy\"");
        assertThat(isolated).contains("content=\"default-src &#39;self&#39; &amp; data:\">");
        assertThat(isolated).contains("<meta name=\"referrer\" content=\"no-referrer\">");
        assertThat(isolated).contains("<html><body>Tablero</body></html>");
    }

    @Test
    void agregaDoctypeCuandoNoExisteYOmitePoliticaRemotaEnBlanco() {
        String isolated = AnalyticsHtml.isolate("<html><body>Tablero</body></html>", "  ");

        assertThat(isolated).startsWith("<!doctype html>");
        assertThat(isolated).contains("<html><body>Tablero</body></html>");
        assertThat(isolated).doesNotContain("default-src 'self'");
    }
}
