package dev.silentreview.idea;

/** Geometry only: negative drag coordinates clamp to the near edge, never to a saved-position sentinel. */
final class OverlayPlacement {
    record Position(int x,int y) {}
    private OverlayPlacement() {}

    static Position fit(int x,int y,int viewportWidth,int viewportHeight,int width,int height,int snapDistance){
        return new Position(axis(x,viewportWidth,width,snapDistance),axis(y,viewportHeight,height,snapDistance));
    }

    static int preserveFarEdge(int coordinate,int oldExtent,int newExtent,int size){
        if(oldExtent>size&&oldExtent!=newExtent&&coordinate==oldExtent-size)return Math.max(0,newExtent-size);
        return coordinate;
    }

    private static int axis(int requested,int extent,int size,int distance){
        int maximum=Math.max(0,extent-size),value=Math.max(0,Math.min(requested,maximum));
        if(Math.min(value,maximum-value)<=Math.max(0,distance))return value<=maximum-value?0:maximum;
        return value;
    }
}
