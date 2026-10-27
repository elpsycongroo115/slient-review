package dev.silentreview.core;

import com.google.gson.*;
import java.util.*;

final class TestFixtures {
    static final String RULES="""
        {"schemaVersion":"1.0","ruleSetId":"erp-test","name":"测试规则","version":"1",
         "rules":[{"id":"JAVA-001","title":"禁止控制台日志","severity":"warning",
         "appliesTo":{"include":["src/**/OrderService.java"],"exclude":["src/vendor/**"]},
         "requirement":"禁止实际调用 System.out.println。注释和字符串不违规。",
         "exceptions":[],"source":{"document":"测试规范","excerpt":"禁止控制台打印"}}]}
        """;
    static Review.Request request(){
        var file=new GitRepository.ChangedFile("src/OrderService.java","src/OrderService.java","M",
            "class OrderService {\n}\n","class OrderService {\n  System.out.println(order);\n}\n",
            "@@ -1,2 +1,3 @@\n class OrderService {\n+  System.out.println(order);\n }\n",Set.of(2),Set.of());
        var changes=new GitRepository.ChangeSet("/repo","feature/test","a".repeat(40),"b".repeat(40),List.of(file),List.of());
        return Review.request(Rules.parse(RULES),changes);
    }
    static JsonObject response(boolean finding){
        JsonObject root=new JsonObject();JsonArray findings=new JsonArray(),coverage=new JsonArray();
        if(finding)findings.add(Review.JSON.toJsonTree(new Review.Finding("JAVA-001","violation","warning","src/OrderService.java","after",2,2,
            "  System.out.println(order);","违反测试规范","使用日志组件","新增调用")));
        coverage.add(Review.JSON.toJsonTree(new Review.Coverage("src/OrderService.java","JAVA-001","reviewed","")));
        root.add("findings",findings);root.add("coverage",coverage);root.add("limitations",new JsonArray());return root;
    }
}
