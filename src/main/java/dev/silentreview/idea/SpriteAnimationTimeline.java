package dev.silentreview.idea;

import java.util.function.LongSupplier;

/** Shared phase for all pet states; hidden time does not advance the animation. */
final class SpriteAnimationTimeline {
    // Matches the 20 exported frames in every /pet/whale_*.png sprite sheet.
    private static final int[] FRAME_MILLIS={420,70,70,140,140,90,90,350,70,90,70,300,70,70,140,140,90,90,300,400};
    private static final long NANOS_PER_MILLI=1_000_000L;
    private static final long PERIOD_NANOS=3_200*NANOS_PER_MILLI;
    private final LongSupplier nanoTime;
    private long elapsedNanos;
    private long resumedAtNanos;
    private boolean running;

    SpriteAnimationTimeline(){this(System::nanoTime);}
    SpriteAnimationTimeline(LongSupplier nanoTime){this.nanoTime=nanoTime;}

    void resume(){
        if(running)return;
        resumedAtNanos=nanoTime.getAsLong();
        running=true;
    }

    void pause(){
        if(!running)return;
        elapsedNanos=currentElapsedNanos();
        running=false;
    }

    int frameIndex(){
        long elapsed=currentElapsedNanos(),end=0;
        for(int frame=0;frame<FRAME_MILLIS.length;frame++){
            end+=FRAME_MILLIS[frame]*NANOS_PER_MILLI;
            if(elapsed<end)return frame;
        }
        return 0;
    }

    private long currentElapsedNanos(){
        if(!running)return elapsedNanos;
        // Subtraction remains valid when System.nanoTime crosses its signed boundary.
        long sinceResume=Math.floorMod(nanoTime.getAsLong()-resumedAtNanos,PERIOD_NANOS);
        return (elapsedNanos+sinceResume)%PERIOD_NANOS;
    }
}
