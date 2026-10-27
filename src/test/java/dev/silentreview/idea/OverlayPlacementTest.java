package dev.silentreview.idea;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverlayPlacementTest {
    private OverlayPlacement.Position fit(int x,int y){return OverlayPlacement.fit(x,y,1000,800,144,210,16);}
    @Test void draggingBeyondTopOrLeftDoesNotJumpToTheOppositeEdge(){
        assertEquals(new OverlayPlacement.Position(0,0),fit(-30,-40));
        assertEquals(new OverlayPlacement.Position(0,300),fit(-1,300));
        assertEquals(new OverlayPlacement.Position(300,0),fit(300,-1));
    }
    @Test void allFourEdgesAndCornersSnapWithinTheSameDistance(){
        assertEquals(new OverlayPlacement.Position(0,300),fit(12,300));
        assertEquals(new OverlayPlacement.Position(856,300),fit(845,300));
        assertEquals(new OverlayPlacement.Position(300,0),fit(300,15));
        assertEquals(new OverlayPlacement.Position(300,590),fit(300,580));
        assertEquals(new OverlayPlacement.Position(856,590),fit(900,900));
    }
    @Test void freeDraggingAwayFromEdgesKeepsTheRequestedPosition(){assertEquals(new OverlayPlacement.Position(200,250),fit(200,250));}
    @Test void narrowViewportsClampAndUseTheClosestEdge(){
        assertEquals(new OverlayPlacement.Position(0,0),OverlayPlacement.fit(50,50,100,100,144,210,16));
        assertEquals(new OverlayPlacement.Position(20,0),OverlayPlacement.fit(13,1,164,220,144,210,16));
    }
    @Test void rightAndBottomAnchorsFollowWindowResizingWhileOtherPositionsStay(){
        assertEquals(1056,OverlayPlacement.preserveFarEdge(856,1000,1200,144));
        assertEquals(690,OverlayPlacement.preserveFarEdge(590,800,900,210));
        assertEquals(0,OverlayPlacement.preserveFarEdge(0,1000,1200,144));
        assertEquals(200,OverlayPlacement.preserveFarEdge(200,1000,1200,144));
        assertEquals(0,OverlayPlacement.preserveFarEdge(0,100,1000,144));
    }
}
