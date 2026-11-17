package com.citypass.movilidad.analytics;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;

/** srcDoc no hereda las cabeceras de BI: las restricciones viajan también dentro del documento. */
final class AnalyticsHtml {
    static final String POLICY = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "img-src data:; font-src data:; connect-src 'none'; frame-src 'none'; worker-src 'none'; "
            + "object-src 'none'; base-uri 'none'; form-action 'none'";
    private static final Pattern HTML = Pattern.compile("<html(?:\\s|>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE = Pattern.compile("^\\s*<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);

    private AnalyticsHtml() { }

    static String isolate(String html, String upstreamPolicy) {
        if (html == null || !HTML.matcher(html).find()) return null;
        String meta = meta(POLICY) + "<meta name=\"referrer\" content=\"no-referrer\">";
        if (upstreamPolicy != null && !upstreamPolicy.isBlank()) meta += meta(upstreamPolicy);
        var doctype = DOCTYPE.matcher(html);
        int offset = doctype.find() ? doctype.end() : 0;
        return "<!doctype html>" + meta + html.substring(offset);
    }

    private static String meta(String policy) {
        return "<meta http-equiv=\"Content-Security-Policy\" content=\""
                + HtmlUtils.htmlEscape(policy) + "\">";
    }
}
