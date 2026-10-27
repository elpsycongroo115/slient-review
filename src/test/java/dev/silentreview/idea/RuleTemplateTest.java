package dev.silentreview.idea;

import dev.silentreview.core.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleTemplateTest {
    @TempDir Path root;
    @Test void relativePathResolvesFromProjectAndExplicitAbsolutePathIsHonored(){
        assertEquals(root.resolve(".silent-review/rules.json"),RuleTemplate.resolve(root.toString(),".silent-review/rules.json"));
        Path external=root.resolve("team/rules.json");
        assertEquals(external,RuleTemplate.resolve(null,external.toString()));
        assertThrows(IllegalArgumentException.class,()->RuleTemplate.resolve(null,"rules.json"));
        assertThrows(IllegalArgumentException.class,()->RuleTemplate.resolve(root.toString()," "));
    }
    @Test void createsValidEditableRulesAndMatchesJavaAndJavascriptAnywhere() throws Exception {
        Path target=root.resolve(".silent-review/rules.json");
        assertTrue(RuleTemplate.create(target).created());
        var rules=Rules.load(target);assertEquals(2,rules.rules().size());assertTrue(rules.name().contains("示例"));
        assertTrue(rules.rules().get(0).appliesTo("Hello.java"));
        assertTrue(rules.rules().get(0).appliesTo("src/main/java/demo/AnyService.java"));
        assertFalse(rules.rules().get(0).appliesTo("target/generated/AnyService.java"));
        assertTrue(rules.rules().get(1).appliesTo("demo.js"));
        assertTrue(rules.rules().get(1).appliesTo("src/components/demo.jsx"));
        assertFalse(rules.rules().get(1).appliesTo("node_modules/demo.js"));
        assertFalse(rules.rules().get(1).appliesTo("web/vendor/demo.js"));
    }
    @Test void neverOverwritesExistingFilesIncludingInvalidProjectRules() throws Exception {
        Path target=root.resolve("rules.json");Files.writeString(target,"project owned content");
        var created=RuleTemplate.create(target);assertFalse(created.created());
        assertEquals("project owned content",Files.readString(target));
    }
}
