package io.rigger.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * One metric's history.
 *
 * <p>The identifying triple is echoed back so a console that fires several series requests in
 * parallel can match each response to the chart that asked for it without tracking request order.
 *
 * @param points oldest first — the order a chart plots left to right.
 */
public record MetricSeriesResponse(String metric, String namespace, String name, List<Point> points) {

    /** Short field names because a 24h window at 30s is ~2900 of these on the wire. */
    public record Point(Instant t, double v) { }
}
