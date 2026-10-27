package dev.silentreview.core;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** Fixed workflow with bounded batching and retries; no autonomous tools or rule extraction. */
public final class ReviewRunner {
    public static final int MAX_SOURCE_CHARACTERS=120_000,MAX_BATCHES=8,MAX_RETRIES=1;
    public static final int MAX_CONTEXT_CHARACTERS=30_000;
    @FunctionalInterface public interface Client { AiClient.Response call(Review.Request request)throws Exception; }
    public record Plan(List<Review.Request> batches,List<String> limitations) {}
    public record Outcome(Review.Request reference,Review.Result result,String model,JsonObject usage) {}

    public static Plan plan(Rules.Snapshot rules,GitRepository.ChangeSet changes){
        return plan(rules,changes,new RuleContext.Snapshot(List.of(),List.of(),Set.of(),0));
    }
    private static Plan plan(Rules.Snapshot rules,GitRepository.ChangeSet changes,RuleContext.Snapshot context){
        List<Review.Request> batches=new ArrayList<>();
        List<GitRepository.ChangedFile> pending=new ArrayList<>();
        List<String> limits=new ArrayList<>(changes.limitations());limits.addAll(context.limitations());int size=0;
        int sourceBudget=MAX_SOURCE_CHARACTERS-Math.min(MAX_CONTEXT_CHARACTERS,context.characters());
        for(var file:changes.files()){
            if(rules.rules().stream().noneMatch(r->r.appliesTo(file.path())))continue;
            int cost=file.before().length()+file.after().length()+file.diff().length();
            if(cost>sourceBudget){limits.add("单文件与关联上下文超过请求预算，未检查："+file.path());continue;}
            if(size+cost>sourceBudget&&!pending.isEmpty()){
                if(batches.size()<MAX_BATCHES)batches.add(batch(rules,changes,pending,context));
                else pending.forEach(f->limits.add("超过最多 "+MAX_BATCHES+" 批次限制，未检查："+f.path()));
                pending=new ArrayList<>();size=0;
            }
            pending.add(file);size+=cost;
        }
        if(!pending.isEmpty()){
            if(batches.size()<MAX_BATCHES)batches.add(batch(rules,changes,pending,context));
            else pending.forEach(f->limits.add("超过最多 "+MAX_BATCHES+" 批次限制，未检查："+f.path()));
        }
        return new Plan(List.copyOf(batches),List.copyOf(limits));
    }
    private static Review.Request batch(Rules.Snapshot rules,GitRepository.ChangeSet original,List<GitRepository.ChangedFile> files,RuleContext.Snapshot context){
        return Review.request(rules,new GitRepository.ChangeSet(original.root(),original.branch(),original.base(),original.target(),List.copyOf(files),List.of()),context);
    }
    public Outcome run(Rules.Snapshot rules,GitRepository.ChangeSet changes,Client client,BooleanSupplier canceled,Consumer<String> progress){
        checkCanceled(canceled);
        progress.accept("正在按规则收集目标提交中的关联文件上下文…");
        RuleContext.Snapshot context=RuleContext.capture(rules,changes,canceled,MAX_CONTEXT_CHARACTERS);
        Plan plan=plan(rules,changes,context);List<Review.Result> results=new ArrayList<>();JsonArray usage=new JsonArray();
        Set<String> models=new LinkedHashSet<>();
        for(int i=0;i<plan.batches().size();i++){
            checkCanceled(canceled);Review.Request request=plan.batches().get(i);
            progress.accept("正在审查第 "+(i+1)+" / "+plan.batches().size()+" 批（"+request.requiredChecks().size()+" 项检查）");
            try{
                AiClient.Response response=null;
                for(int attempt=0;attempt<=MAX_RETRIES;attempt++){
                    checkCanceled(canceled);
                    try{response=client.call(request);break;}
                    catch(AiClient.Failure e){
                        if(!e.retryable()||attempt==MAX_RETRIES)throw e;
                        progress.accept("接口暂时不可用，准备有限重试…");
                        for(int wait=0;wait<10;wait++){checkCanceled(canceled);Thread.sleep(100);}
                    }
                }
                if(response==null)throw new IllegalStateException("没有得到模型响应");
                checkCanceled(canceled);results.add(Review.validate(request,response.content()));models.add(response.model());
                if(response.usage()!=null)usage.add(response.usage().deepCopy());
            }catch(CancellationException e){throw e;
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("检查已中断");
            }catch(Exception e){
                String reason=e.getMessage()==null?"该批次检查失败":e.getMessage();
                results.add(new Review.Result(List.of(),request.requiredChecks().stream().map(c->new Review.Coverage(c.filePath(),c.ruleId(),"failed",reason)).toList(),List.of(reason),Review.Status.FAILED));
                // Invalid credentials cannot be recovered by issuing the remaining requests.
                if(e instanceof AiClient.Failure failure&&!failure.retryable()){
                    for(int j=i+1;j<plan.batches().size();j++){
                        Review.Request remaining=plan.batches().get(j);
                        results.add(new Review.Result(List.of(),remaining.requiredChecks().stream().map(c->new Review.Coverage(c.filePath(),c.ruleId(),"skipped","接口不可用，已停止后续请求")).toList(),List.of("接口不可用，后续批次未执行"),Review.Status.FAILED));
                    }
                    break;
                }
            }
        }
        checkCanceled(canceled);
        LinkedHashSet<Review.Check> expected=new LinkedHashSet<>();
        for(var file:changes.files())for(var rule:rules.rules())if(rule.appliesTo(file.path()))expected.add(new Review.Check(file.path(),rule.id()));
        Set<Review.Check> covered=new HashSet<>();
        for(var result:results)for(var item:result.coverage())covered.add(new Review.Check(item.filePath(),item.ruleId()));
        List<Review.Coverage> omitted=expected.stream().filter(check->!covered.contains(check))
            .map(check->new Review.Coverage(check.filePath(),check.ruleId(),"skipped","受请求上下文或批次数量上限限制，未送入 AI；请缩小范围后重试")).toList();
        if(!omitted.isEmpty())results.add(new Review.Result(List.of(),omitted,List.of("存在因预算或批数上限而未执行的检查，详见覆盖记录"),Review.Status.PARTIAL));
        Review.Result merged=merge(results,plan.limitations());JsonObject usageObject=null;
        if(!usage.isEmpty()){usageObject=new JsonObject();usageObject.add("batches",usage);}
        JsonObject trace=new JsonObject();trace.addProperty("baseline",changes.base());trace.addProperty("target",changes.target());
        trace.add("contextFiles",Review.JSON.toJsonTree(context.entries()));
        trace.add("incompleteContextChecks",Review.JSON.toJsonTree(context.incompleteChecks()));
        Review.Request reference=new Review.Request(rules,changes,List.copyOf(expected),Review.JSON.toJson(trace));
        return new Outcome(reference,merged,models.isEmpty()?(plan.batches().isEmpty()?"未调用 AI":"未获得有效 AI 响应"):String.join(", ",models),usageObject);
    }
    public static Review.Result merge(List<Review.Result> results,List<String> limits){
        List<Review.Finding> findings=new ArrayList<>();List<Review.Coverage> coverage=new ArrayList<>();List<String> limitations=new ArrayList<>(limits);
        Set<String> seen=new HashSet<>();boolean partial=!limits.isEmpty(),allFailed=!results.isEmpty();
        for(var result:results){
            allFailed&=result.status()==Review.Status.FAILED;
            partial|=result.status()!=Review.Status.COMPLETED;
            for(var finding:result.findings())if(seen.add(finding.ruleId()+"\0"+finding.filePath()+"\0"+finding.side()+"\0"+finding.startLine()+"\0"+finding.evidence()))findings.add(finding);
            coverage.addAll(result.coverage());limitations.addAll(result.limitations());
        }
        Review.Status status=allFailed?Review.Status.FAILED:partial?Review.Status.PARTIAL:results.isEmpty()?Review.Status.NOT_APPLICABLE:Review.Status.COMPLETED;
        return new Review.Result(List.copyOf(findings),List.copyOf(coverage),List.copyOf(new LinkedHashSet<>(limitations)),status);
    }
    private static void checkCanceled(BooleanSupplier canceled){if(canceled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("检查已取消");}
}
