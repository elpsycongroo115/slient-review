package dev.silentreview.core;

import java.util.List;

/** Aggregate across repositories without allowing the last (or empty) result to hide earlier failures/findings. */
public final class ReviewBatch {
    private ReviewBatch() {}
    public record Summary(Review.Status status,int completed,int partial,int failed,int notApplicable,int findings) {}
    public static Summary summarize(List<Review.Result> results,int preparationFailures){
        int completed=0,partial=0,failed=preparationFailures,notApplicable=0,findings=0;
        for(var result:results){
            findings+=result.findings().size();
            switch(result.status()){
                case COMPLETED -> completed++;
                case PARTIAL -> partial++;
                case FAILED -> failed++;
                case NOT_APPLICABLE -> notApplicable++;
                default -> throw new IllegalArgumentException("不能汇总尚未完成或已取消的审查");
            }
        }
        Review.Status status=failed>0?(completed+partial+notApplicable>0?Review.Status.PARTIAL:Review.Status.FAILED)
            :partial>0?Review.Status.PARTIAL:completed>0?Review.Status.COMPLETED:Review.Status.NOT_APPLICABLE;
        return new Summary(status,completed,partial,failed,notApplicable,findings);
    }
}
