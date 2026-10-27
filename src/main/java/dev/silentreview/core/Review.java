package dev.silentreview.core;

import com.google.gson.*;
import java.time.Instant;
import java.util.*;

public final class Review {
    private Review() {}
    public static final String INSTRUCTION_VERSION = "1.1.0";
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public enum Status { QUEUED, PREPARING, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELED, NOT_APPLICABLE }
    public record Check(String filePath, String ruleId) {}
    public record Request(Rules.Snapshot rules, GitRepository.ChangeSet changes, List<Check> requiredChecks, String json) {}
    public record Finding(String ruleId, String kind, String severity, String filePath, String side,
                          int startLine, int endLine, String evidence, String explanation, String suggestion, String changeRelation) {}
    public record Coverage(String filePath, String ruleId, String status, String reason) {}
    public record Result(List<Finding> findings, List<Coverage> coverage, List<String> limitations, Status status) {}
    public record Report(String id, String createdAt, String root, String branch, String base, String target,
                         String scope, String ruleSetId, String ruleSetName, String ruleSetVersion, String fingerprint,
                         List<JsonObject> ruleDefinitions, Map<String,String> previousPaths,
                         String instructionVersion, String model, JsonObject usage, Result result) {}

    public static Request request(Rules.Snapshot rules, GitRepository.ChangeSet changes) {
        return request(rules,changes,new RuleContext.Snapshot(List.of(),List.of(),Set.of(),0));
    }
    public static Request request(Rules.Snapshot rules, GitRepository.ChangeSet changes,RuleContext.Snapshot context) {
        JsonObject payload = new JsonObject();
        payload.addProperty("instructionVersion", INSTRUCTION_VERSION);
        payload.addProperty("baseline", changes.base()); payload.addProperty("target", changes.target());
        payload.addProperty("ruleSetFingerprint", rules.fingerprint());
        JsonArray definitions = new JsonArray(), files = new JsonArray();
        Set<String> used = new HashSet<>(); List<Check> checks = new ArrayList<>();
        List<String> limits = new ArrayList<>(changes.limitations());
        int totalCharacters = 0;
        for (GitRepository.ChangedFile file : changes.files()) {
            List<Rules.Rule> applicable = rules.rules().stream().filter(r -> r.appliesTo(file.path())).toList();
            if (applicable.isEmpty()) continue;
            int size = file.before().length() + file.after().length() + file.diff().length();
            if (totalCharacters + size > 120_000) { limits.add("请求上下文超限，未检查：" + file.path()); continue; }
            totalCharacters += size;
            for (Rules.Rule rule : applicable) {
                checks.add(new Check(file.path(), rule.id()));
                if (used.add(rule.id())) definitions.add(rule.definition().deepCopy());
            }
            JsonObject source = new JsonObject();
            source.addProperty("filePath", file.path()); source.addProperty("beforePath", file.beforePath());
            source.addProperty("changeType", file.status()); source.addProperty("diff", file.diff());
            source.addProperty("before", numbered(file.before())); source.addProperty("after", numbered(file.after()));
            files.add(source);
        }
        payload.add("rules", definitions); payload.add("files", files);
        payload.add("requiredChecks", JSON.toJsonTree(checks));
        Set<Check> requested=new HashSet<>(checks);
        Map<String,String> contents=new HashMap<>();
        for(var entry:context.entries())if(entry.status().equals("present")&&!entry.content().isEmpty())contents.put(entry.path(),entry.content());
        Set<String> attached=new HashSet<>();JsonArray related=new JsonArray();
        for(var entry:context.entries()){
            if(!requested.contains(new Check(entry.filePath(),entry.ruleId())))continue;
            // A shared context may have first appeared in another AI batch. Attach its text in this batch too.
            String text=entry.status().equals("present")&&attached.add(entry.path())?contents.getOrDefault(entry.path(),entry.content()):"";
            related.add(JSON.toJsonTree(new RuleContext.Entry(entry.ruleId(),entry.filePath(),entry.requestedPath(),entry.path(),entry.status(),text,entry.reason(),entry.candidates())));
        }
        payload.add("contextFiles",related);
        payload.add("incompleteContextChecks",JSON.toJsonTree(context.incompleteChecks().stream().filter(requested::contains).toList()));
        payload.add("limitations", JSON.toJsonTree(limits));
        GitRepository.ChangeSet limited = new GitRepository.ChangeSet(changes.root(), changes.branch(), changes.base(), changes.target(), changes.files(), List.copyOf(limits));
        return new Request(rules, limited, List.copyOf(checks), JSON.toJson(payload));
    }

    public static Result validate(Request request, String response) {
        JsonObject root;
        try { root = StrictJson.parse(response).getAsJsonObject(); }
        catch (Exception e) { throw new IllegalArgumentException("AI 未返回有效 JSON 对象", e); }
        if (!root.has("findings") || !root.get("findings").isJsonArray() || !root.has("coverage") || !root.get("coverage").isJsonArray())
            throw new IllegalArgumentException("AI 响应缺少 findings 或 coverage 数组");
        Set<Check> expected = new HashSet<>(request.requiredChecks());
        Set<Check> incompleteContext=incompleteContextChecks(request);
        List<String> limits = new ArrayList<>(request.changes().limitations());
        List<Coverage> coverage = new ArrayList<>(); Set<Check> seen = new HashSet<>();
        boolean partial = !limits.isEmpty();
        for (JsonElement item : root.getAsJsonArray("coverage")) {
            try {
                JsonObject o = item.getAsJsonObject();
                Check key = new Check(Rules.required(o,"filePath"), Rules.required(o,"ruleId"));
                String status = Rules.required(o,"status");
                if (!expected.contains(key) || !seen.add(key) || !Set.of("reviewed","needs_context","skipped","failed").contains(status))
                    throw new IllegalArgumentException("无效或重复 coverage");
                String reason = o.has("reason") && o.get("reason").isJsonPrimitive() ? o.get("reason").getAsString() : "";
                if(incompleteContext.contains(key)&&status.equals("reviewed")){
                    status="needs_context";reason="关联上下文未完整提供，不能视为完整检查。"+(reason.isBlank()?"":" "+reason);
                }
                coverage.add(new Coverage(key.filePath(),key.ruleId(),status,reason));
                if (!status.equals("reviewed")) partial = true;
            } catch (RuntimeException e) { partial = true; limits.add("AI 返回了无法校验的覆盖记录"); }
        }
        for (Check check : expected) if (!seen.contains(check)) {
            partial = true; coverage.add(new Coverage(check.filePath(),check.ruleId(),"failed","AI 未返回此检查的覆盖记录"));
        }
        List<Finding> findings = new ArrayList<>(); Set<String> dedup = new HashSet<>();
        for (JsonElement item : root.getAsJsonArray("findings")) {
            try {
                JsonObject o = item.getAsJsonObject();
                Finding f = JSON.fromJson(o, Finding.class);
                if (!expected.contains(new Check(f.filePath(),f.ruleId()))) throw new IllegalArgumentException();
                String findingPath=f.filePath();
                GitRepository.ChangedFile file = request.changes().files().stream().filter(x -> x.path().equals(findingPath)).findFirst().orElseThrow();
                if (!Set.of("violation","suggestion","needs_confirmation").contains(f.kind())
                    || !Set.of("error","warning","info").contains(f.severity())
                    || !Set.of("after","before").contains(f.side())) throw new IllegalArgumentException();
                String content = f.side().equals("after") ? file.after() : file.before();
                String[] lines = content.split("\\R",-1);
                if (f.startLine()<1 || f.endLine()<f.startLine() || f.endLine()>lines.length || blank(f.evidence())
                    || blank(f.explanation()) || blank(f.suggestion()) || blank(f.changeRelation())) throw new IllegalArgumentException();
                String selected = String.join("\n", Arrays.copyOfRange(lines,f.startLine()-1,f.endLine()));
                if (!selected.strip().equals(f.evidence().strip().replace("\r\n","\n"))) throw new IllegalArgumentException();
                Set<Integer> changed = f.side().equals("after") ? file.addedLines() : file.deletedLines();
                int start=f.startLine(), end=f.endLine();
                boolean intersects = changed.stream().anyMatch(line -> line>=start && line<=end);
                if (!intersects) {
                    partial = true;
                    f = new Finding(f.ruleId(),"needs_confirmation",f.severity(),f.filePath(),f.side(),f.startLine(),f.endLine(),f.evidence(),
                        "位置未直接命中变更行，需要人工确认与本次变更的关系。" + f.explanation(),f.suggestion(),f.changeRelation());
                }
                if (f.kind().equals("needs_confirmation")) partial = true;
                String key = f.ruleId()+"\0"+f.filePath()+"\0"+f.side()+"\0"+f.startLine()+"\0"+f.evidence();
                if (dedup.add(key)) findings.add(f);
            } catch (RuntimeException e) { partial = true; limits.add("AI 返回的问题无法核对规则、位置或代码证据，已拒绝采信"); }
        }
        if (root.has("limitations")) {
            if (!root.get("limitations").isJsonArray()) { partial=true; limits.add("AI limitations 格式错误"); }
            else for (JsonElement item : root.getAsJsonArray("limitations")) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && !item.getAsString().isBlank()) {
                    partial=true; limits.add(item.getAsString());
                }
            }
        }
        Status status = expected.isEmpty() && limits.isEmpty() ? Status.NOT_APPLICABLE : partial ? Status.PARTIAL : Status.COMPLETED;
        return new Result(List.copyOf(findings), List.copyOf(coverage), List.copyOf(limits), status);
    }

    public static Report report(Request request, String scope, String model, JsonObject usage, Result result) {
        scope=scopeWithContext(scope,request);
        return new Report(UUID.randomUUID().toString(),Instant.now().toString(),request.changes().root(),request.changes().branch(),
            request.changes().base(),request.changes().target(),scope,request.rules().id(),request.rules().name(),request.rules().version(),
            request.rules().fingerprint(),request.rules().rules().stream().map(r->r.definition().deepCopy()).toList(),
            request.changes().files().stream().collect(java.util.stream.Collectors.toMap(GitRepository.ChangedFile::path,GitRepository.ChangedFile::beforePath)),
            INSTRUCTION_VERSION,model,usage,result);
    }
    public static String numbered(String content) {
        String[] lines=content.split("\\R",-1); StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++) out.append(i+1).append(": ").append(lines[i]).append('\n');
        return out.toString();
    }
    private static boolean blank(String s) { return s==null || s.isBlank(); }
    private static String scopeWithContext(String scope,Request request){
        if(request.json()==null||request.json().isBlank())return scope;
        JsonObject payload=StrictJson.parse(request.json()).getAsJsonObject();
        if(!payload.has("contextFiles")||payload.getAsJsonArray("contextFiles").isEmpty())return scope;
        Set<String> descriptions=new LinkedHashSet<>();
        for(JsonElement item:payload.getAsJsonArray("contextFiles")){
            JsonObject entry=item.getAsJsonObject();String path=entry.get("path").getAsString();
            if(path.isBlank())path=entry.get("requestedPath").getAsString();
            String state=switch(entry.get("status").getAsString()){
                case "present"->"已读取";case "missing"->"提交中缺失";case "ambiguous"->"候选不唯一";default->"未能读取";
            };
            descriptions.add(entry.get("ruleId").getAsString()+" / "+path+" ["+state+"]");
        }
        return scope+"\n关联上下文（目标提交）："+String.join("；",descriptions);
    }
    private static Set<Check> incompleteContextChecks(Request request){
        if(request.json()==null||request.json().isBlank())return Set.of();
        JsonObject payload=StrictJson.parse(request.json()).getAsJsonObject();
        if(!payload.has("incompleteContextChecks"))return Set.of();
        Set<Check> result=new HashSet<>();
        for(JsonElement item:payload.getAsJsonArray("incompleteContextChecks")){
            JsonObject check=item.getAsJsonObject();result.add(new Check(requiredContext(check,"filePath"),requiredContext(check,"ruleId")));
        }
        return result;
    }
    private static String requiredContext(JsonObject object,String key){return Rules.required(object,key);}
}
