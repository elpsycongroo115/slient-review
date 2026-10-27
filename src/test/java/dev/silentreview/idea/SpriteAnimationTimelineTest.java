package dev.silentreview.idea;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SpriteAnimationTimelineTest {
    private static final long MS=1_000_000L;

    @Test void respectsEveryExportedFrameBoundaryAndWrapsWithoutAnExtraHold(){
        int[] durations={420,70,70,140,140,90,90,350,70,90,70,300,70,70,140,140,90,90,300,400};
        var now=new AtomicLong(0);
        var timeline=new SpriteAnimationTimeline(now::get);
        timeline.resume();
        long start=0;
        for(int frame=0;frame<durations.length;frame++){
            now.set(start*MS);assertEquals(frame,timeline.frameIndex(),"frame start");
            now.set((start+durations[frame])*MS-1);assertEquals(frame,timeline.frameIndex(),"frame end");
            start+=durations[frame];
        }
        assertEquals(3200,start);
        now.set(start*MS);assertEquals(0,timeline.frameIndex());
        now.set((start*100+490)*MS);assertEquals(2,timeline.frameIndex());
    }

    @Test void delayedRepaintsCatchUpUsingElapsedTimeRatherThanCountingTimerTicks(){
        var now=new AtomicLong(0);
        var timeline=new SpriteAnimationTimeline(now::get);
        timeline.resume();
        assertEquals(0,timeline.frameIndex());
        now.set(1730*MS);assertEquals(11,timeline.frameIndex());
        now.set(3200*MS+840*MS);assertEquals(5,timeline.frameIndex());
    }

    @Test void repeatedVisibilityUpdatesDoNotResetPhaseAndHiddenTimeIsExcluded(){
        var now=new AtomicLong(0);
        var timeline=new SpriteAnimationTimeline(now::get);
        timeline.resume();
        now.set(450*MS);timeline.resume();assertEquals(1,timeline.frameIndex());
        timeline.pause();timeline.pause();
        now.set(50_000*MS);assertEquals(1,timeline.frameIndex());
        timeline.resume();
        now.addAndGet(39*MS);assertEquals(1,timeline.frameIndex());
        now.addAndGet(MS);assertEquals(2,timeline.frameIndex());
        timeline.pause();now.addAndGet(10_000*MS);timeline.resume();
        assertEquals(2,timeline.frameIndex());
    }

    @Test void clockSignBoundaryDoesNotChangeThePhase(){
        var now=new AtomicLong(Long.MAX_VALUE-200*MS);
        var timeline=new SpriteAnimationTimeline(now::get);
        timeline.resume();
        now.addAndGet(490*MS);assertEquals(2,timeline.frameIndex());
        timeline.pause();now.addAndGet(20_000*MS);timeline.resume();
        now.addAndGet(70*MS);assertEquals(3,timeline.frameIndex());
    }
}
