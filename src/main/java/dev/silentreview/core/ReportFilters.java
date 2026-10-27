package dev.silentreview.core;

import java.util.List;
import java.util.Map;

/** Filters stored reports without changing their result, resolution or finding indices. */
public final class ReportFilters {
    private ReportFilters() {}
    public static List<Review.Report> select(List<Review.Report> reports,Map<String,Reports.Resolution> resolutions,
                                            String root,Reports.Decision decision,String severity) {
        return reports.stream().filter(r->root==null||root.equals(r.root()))
            .filter(r->decision==null||Reports.resolution(r.id(),resolutions).decision()==decision)
            .filter(r->severity==null||r.result().findings().stream().anyMatch(f->severity.equals(f.severity())))
            .toList();
    }
}
