package dev.silentreview.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable, externally generated rule set. Never extracts rules from documents. */
public final class Rules {
    private Rules() {}
    public record Rule(String id, String title, String severity, List<String> includes,
                       List<String> excludes, String requirement, JsonObject definition) {
        public List<String> contextFiles(){return List.copyOf(strings(definition,"contextFiles",false));}
        public Optional<Map<String,String>> bindings(String path){
            if(excludes.stream().anyMatch(p->glob(p).matcher(path).matches()))return Optional.empty();
            for(String include:includes){Optional<Map<String,String>> binding=bind(include,path);if(binding.isPresent())return binding;}
            return Optional.empty();
        }
        public boolean appliesTo(String path) {
            return bindings(path).isPresent();
        }
    }
    public record Snapshot(String id, String name, String version, String fingerprint,
                           List<Rule> rules, String json) {}

    public static Snapshot load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("规则文件不存在：" + path);
        if (Files.size(path) > 2_000_000) throw new IOException("规则集超过 2 MB 上限");
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static Snapshot parse(String text) {
        try {
            JsonObject root = StrictJson.parse(text).getAsJsonObject();
            if (!"1.0".equals(required(root, "schemaVersion"))) fail("不支持的 schemaVersion，只接受 1.0");
            String id = required(root, "ruleSetId"), name = required(root, "name"), version = required(root, "version");
            if (!root.has("rules") || !root.get("rules").isJsonArray()) fail("rules 必须是数组");
            List<Rule> rules = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonElement item : root.getAsJsonArray("rules")) {
                JsonObject r = item.getAsJsonObject();
                String rid = required(r, "id");
                if (!ids.add(rid)) fail("重复规则编号：" + rid);
                String severity = required(r, "severity");
                if (!Set.of("error", "warning", "info").contains(severity)) fail(rid + " 的 severity 无效");
                JsonObject applies = r.getAsJsonObject("appliesTo");
                if (applies == null) fail(rid + " 缺少 appliesTo");
                List<String> include = strings(applies, "include", true), exclude = strings(applies, "exclude", false);
                if (include.isEmpty()) fail(rid + " 的 include 不能为空");
                include.forEach(Rules::glob); exclude.forEach(Rules::glob);
                JsonObject source = r.getAsJsonObject("source");
                if (source == null) fail(rid + " 缺少 source");
                required(source, "document"); required(source, "excerpt");
                strings(r, "exceptions", false);
                strings(r,"contextFiles",false).forEach(Rules::glob);
                String requirement=required(r,"requirement");
                rules.add(new Rule(rid, required(r, "title"), severity, List.copyOf(include), List.copyOf(exclude),
                                   requirement, r.deepCopy()));
            }
            if (rules.isEmpty()) fail("规则集不能为空");
            return new Snapshot(id, name, version, sha256(text), List.copyOf(rules), text);
        } catch (IllegalArgumentException e) { throw e;
        } catch (Exception e) { throw new IllegalArgumentException("规则集格式无效：" + e.getMessage(), e); }
    }

    public static String required(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()
            || !obj.getAsJsonPrimitive(key).isString() || obj.get(key).getAsString().isBlank())
            throw new IllegalArgumentException("缺少或无效字符串字段：" + key);
        return obj.get(key).getAsString();
    }
    private static List<String> strings(JsonObject obj, String key, boolean required) {
        if (!obj.has(key)) { if (required) fail("缺少数组字段：" + key); return List.of(); }
        if (!obj.get(key).isJsonArray()) fail(key + " 必须是数组");
        List<String> result = new ArrayList<>();
        for (JsonElement e : obj.getAsJsonArray(key)) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isBlank()) fail(key + " 包含无效字符串");
            result.add(e.getAsString());
        }
        return result;
    }
    private record Template(Pattern pattern,Map<String,String> groups) {}
    public static Pattern glob(String glob){return template(glob).pattern();}
    public static Optional<Map<String,String>> bind(String pattern,String path){
        Template template=template(pattern);var matcher=template.pattern().matcher(path);
        if(!matcher.matches())return Optional.empty();
        Map<String,String> bindings=new LinkedHashMap<>();template.groups().forEach((name,group)->bindings.put(name,matcher.group(group)));
        return Optional.of(Map.copyOf(bindings));
    }
    /** Named placeholders are literal path captures; the legacy whole segment xxx is a generic alias. */
    public static Optional<String> substitute(String path,Map<String,String> bindings){
        String normalized=legacyPlaceholders(path);var matcher=Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}").matcher(normalized);
        StringBuilder output=new StringBuilder();int previous=0;
        while(matcher.find()){
            String value=bindings.get(matcher.group(1));if(value==null)return Optional.empty();
            output.append(normalized,previous,matcher.start()).append(value);previous=matcher.end();
        }
        output.append(normalized.substring(previous));return Optional.of(output.toString());
    }
    private static String legacyPlaceholders(String path){
        return String.join("/",Arrays.stream(path.split("/",-1)).map(part->part.equals("xxx")?"{xxx}":part).toList());
    }
    private static Template template(String glob) {
        if (glob.isBlank() || glob.startsWith("/") || glob.contains("\\") || glob.contains(":")
            || Arrays.asList(glob.split("/", -1)).contains("..") || glob.contains("[") || glob.contains("?"))
            throw new IllegalArgumentException("不支持的路径规则：" + glob);
        glob=legacyPlaceholders(glob);
        StringBuilder regex = new StringBuilder("^");Map<String,String> groups=new LinkedHashMap<>();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if(c=='{'){
                int end=glob.indexOf('}',i);String name=end<0?"":glob.substring(i+1,end);
                if(!name.matches("[A-Za-z][A-Za-z0-9_]*"))throw new IllegalArgumentException("无效的路径占位符："+glob);
                String group=groups.get(name);
                if(group==null){group="p"+groups.size();groups.put(name,group);regex.append("(?<").append(group).append(">[^/]+?)");}
                else regex.append("\\k<").append(group).append('>');
                i=end;
            }else if(c=='}')throw new IllegalArgumentException("无效的路径占位符："+glob);
            else if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { regex.append("(?:.*/)?"); i++; }
                else regex.append(".*");
            } else if (c == '*') regex.append("[^/]*");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return new Template(Pattern.compile(regex.append('$').toString()),Map.copyOf(groups));
    }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
