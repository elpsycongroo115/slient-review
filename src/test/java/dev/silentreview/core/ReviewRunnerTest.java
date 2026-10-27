package dev.silentreview.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewRunnerTest {
    private GitRepository.ChangeSet changes(int count,int characters){
        List<GitRepository.ChangedFile> files=new ArrayList<>();
        for(int i=0;i<count;i++)files.add(new GitRepository.ChangedFile("src/p"+i+"/OrderService.java","src/p"+i+"/OrderService.java","A","","x".repeat(characters),"",Set.of(1),Set.of()));
        return new GitRepository.ChangeSet("/repo","feature/test","a".repeat(40),"b".repeat(40),List.copyOf(files),List.of());
    }
    private AiClient.Response response(Review.Request request){
        JsonObject json=new JsonObject();json.add("findings",new JsonArray());JsonArray checks=new JsonArray();
        for(var c:request.requiredChecks())checks.add(Review.JSON.toJsonTree(new Review.Coverage(c.filePath(),c.ruleId(),"reviewed","")));
        json.add("coverage",checks);return new AiClient.Response(json.toString(),"fake-model",null);
    }
    @Test void splitsFilesIntoBoundedBatches(){var p=ReviewRunner.plan(Rules.parse(TestFixtures.RULES),changes(3,80_000));assertEquals(3,p.batches().size());assertTrue(p.limitations().isEmpty());}
    @Test void capsBatchCountAndReportsSkippedFiles(){var p=ReviewRunner.plan(Rules.parse(TestFixtures.RULES),changes(10,80_000));assertEquals(8,p.batches().size());assertEquals(2,p.limitations().size());}
    @Test void oversizedSingleFileIsNotSilentlyPassed(){var outcome=new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(1,130_000),r->{fail("must not call AI");return null;},()->false,s->{});assertEquals(Review.Status.PARTIAL,outcome.result().status());assertFalse(outcome.result().limitations().isEmpty());}
    @Test void noApplicableChecksAvoidsNetwork(){AtomicInteger calls=new AtomicInteger();var outcome=new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(0,0),r->{calls.incrementAndGet();return response(r);},()->false,s->{});assertEquals(0,calls.get());assertEquals(Review.Status.NOT_APPLICABLE,outcome.result().status());}
    @Test void successAggregatesCoverageFromEveryBatch(){var outcome=new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(3,80_000),this::response,()->false,s->{});assertEquals(Review.Status.COMPLETED,outcome.result().status());assertEquals(3,outcome.result().coverage().size());assertEquals(3,outcome.reference().requiredChecks().size());}
    @Test void invalidResponseDoesNotDiscardOtherSuccessfulBatch(){AtomicInteger calls=new AtomicInteger();var outcome=new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(2,80_000),r->calls.getAndIncrement()==0?new AiClient.Response("broken","fake",null):response(r),()->false,s->{});assertEquals(Review.Status.PARTIAL,outcome.result().status());assertEquals(2,outcome.result().coverage().size());}
    @Test void transientFailureRetriesOnlyOnce(){AtomicInteger calls=new AtomicInteger();var outcome=new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(1,20),r->{if(calls.incrementAndGet()==1)throw new AiClient.Failure("temporary",true);return response(r);},()->false,s->{});assertEquals(2,calls.get());assertEquals(Review.Status.COMPLETED,outcome.result().status());}
    @Test void exhaustedRetriesReturnFailure(){AtomicInteger calls=new AtomicInteger();var outcome=new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(1,20),r->{calls.incrementAndGet();throw new AiClient.Failure("temporary",true);},()->false,s->{});assertEquals(2,calls.get());assertEquals(Review.Status.FAILED,outcome.result().status());}
    @Test void invalidCredentialsStopLaterBatches(){AtomicInteger calls=new AtomicInteger();var outcome=new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(3,80_000),r->{calls.incrementAndGet();throw new AiClient.Failure("401",false);},()->false,s->{});assertEquals(1,calls.get());assertEquals(Review.Status.FAILED,outcome.result().status());assertEquals(3,outcome.result().coverage().size());}
    @Test void cancellationIsNeverMergedAsCompleted(){AtomicBoolean canceled=new AtomicBoolean();assertThrows(CancellationException.class,()->new ReviewRunner().run(Rules.parse(TestFixtures.RULES),changes(2,80_000),r->{canceled.set(true);return response(r);},canceled::get,s->{}));}
}
