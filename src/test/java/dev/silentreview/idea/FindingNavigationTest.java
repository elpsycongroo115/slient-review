package dev.silentreview.idea;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FindingNavigationTest {
    @TempDir Path directory;
    @Test void unregisteredNestedGitRootInsideProjectIsNavigableWithoutPreparedScope()throws Exception{
        Path project=Files.createDirectory(directory.resolve("workspace"));Path nested=Files.createDirectories(project.resolve("modules/cable"));Files.createDirectory(nested.resolve(".git"));
        assertTrue(FindingNavigation.inProject(nested,List.of(),List.of(project)));
        Path child=Files.createDirectory(nested.resolve("src"));assertFalse(FindingNavigation.inProject(child,List.of(),List.of(project)));
    }
    @Test void contentRootSupportsGitWorktreeMarkerAndRegisteredExternalRootsRemainAllowed()throws Exception{
        Path project=Files.createDirectory(directory.resolve("workspace"));Path content=Files.createDirectory(directory.resolve("content"));Path worktree=Files.createDirectory(content.resolve("hdpos46"));Files.writeString(worktree.resolve(".git"),"gitdir: ../../common/.git/worktrees/hdpos46");
        assertTrue(FindingNavigation.inProject(worktree,List.of(),List.of(project,content)));
        Path registered=Files.createDirectory(directory.resolve("registered-external"));assertTrue(FindingNavigation.inProject(registered,List.of(registered),List.of(project)));
    }
    @Test void unregisteredGitRootOutsideProjectDirectoriesIsRejected()throws Exception{
        Path project=Files.createDirectory(directory.resolve("workspace"));Path external=Files.createDirectory(directory.resolve("external"));Files.createDirectory(external.resolve(".git"));
        assertFalse(FindingNavigation.inProject(external,List.of(),List.of(project)));
    }
    @Test void projectSubdirectoryAllowsOnlyItsNearestGitAncestorNotOuterOrSiblingRoots()throws Exception{
        Path outer=Files.createDirectory(directory.resolve("outer"));Files.createDirectory(outer.resolve(".git"));
        Path inner=Files.createDirectory(outer.resolve("module"));Path hint=Files.createDirectory(inner.resolve("src"));
        Path sibling=Files.createDirectory(outer.resolve("other"));Files.createDirectory(sibling.resolve(".git"));
        assertTrue(FindingNavigation.inProject(outer,List.of(),List.of(hint)));
        assertFalse(FindingNavigation.inProject(sibling,List.of(),List.of(hint)));
        Files.createDirectory(inner.resolve(".git"));
        assertTrue(FindingNavigation.inProject(inner,List.of(),List.of(hint)));
        assertFalse(FindingNavigation.inProject(outer,List.of(),List.of(hint)));
    }
    @Test void sameRelativeFileInTwoRepositoriesNeverCrossesReportRoot()throws Exception{
        Path one=Files.createDirectories(directory.resolve("repo-one/src")),two=Files.createDirectories(directory.resolve("repo-two/src"));
        Files.writeString(one.resolve("OrderService.java"),"one");Files.writeString(two.resolve("OrderService.java"),"two");
        var first=FindingNavigation.currentFile(one.getParent(),"src/OrderService.java",Map.of());
        var second=FindingNavigation.currentFile(two.getParent(),"src/OrderService.java",Map.of());
        assertEquals(one.resolve("OrderService.java"),first.path());assertEquals(two.resolve("OrderService.java"),second.path());
    }
    @Test void renamedFilePrefersCurrentPathAndOnlyFallsBackInsideSameRoot()throws Exception{
        Path root=Files.createDirectories(directory.resolve("repo/src")).getParent();
        Path old=root.resolve("src/Old.java"),current=root.resolve("src/New.java");Files.writeString(old,"old");Files.writeString(current,"new");
        Map<String,String> renames=Map.of("src/New.java","src/Old.java");
        var target=FindingNavigation.currentFile(root,"src/New.java",renames);assertEquals(current,target.path());assertFalse(target.renamedFallback());
        Files.delete(current);target=FindingNavigation.currentFile(root,"src/New.java",renames);assertEquals(old,target.path());assertTrue(target.renamedFallback());
    }
    @Test void oldSidePathCanFindRecordedNewNameInSameRoot()throws Exception{
        Path root=Files.createDirectories(directory.resolve("repo/src")).getParent();Path current=root.resolve("src/New.java");Files.writeString(current,"new");
        var target=FindingNavigation.currentFile(root,"src/Old.java",Map.of("src/New.java","src/Old.java"));
        assertEquals(current,target.path());assertTrue(target.renamedFallback());
    }
    @Test void deletedFileDoesNotFallBackToAnotherRepositoryWithSameName()throws Exception{
        Path root=Files.createDirectory(directory.resolve("repo-one"));Path other=Files.createDirectories(directory.resolve("repo-two/src"));Files.writeString(other.resolve("OrderService.java"),"other");
        var error=assertThrows(FileNotFoundException.class,()->FindingNavigation.currentFile(root,"src/OrderService.java",Map.of()));
        assertTrue(error.getMessage().contains("可能已删除"));assertTrue(error.getMessage().contains("未搜索其他仓库"));
    }
    @Test void normalizedTraversalAndAbsolutePathsCannotEscapeReportRoot()throws Exception{
        Path root=Files.createDirectory(directory.resolve("repo"));
        for(String path:new String[]{"../outside.java","src/../../outside.java","/outside.java","C:\\outside.java","\\\\server\\outside.java"})
            assertThrows(IllegalArgumentException.class,()->FindingNavigation.currentFile(root,path,Map.of()),path);
        assertThrows(IllegalArgumentException.class,()->FindingNavigation.currentFile(root,"src/New.java",Map.of("src/New.java","../outside.java")));
    }
    @Test void originalEvidenceLineMatchesExactlyEvenIfOtherCopiesExist(){
        var line=FindingNavigation.currentLine("header\n  log(order);\nfooter\n  log(order);","  log(order);",2);
        assertEquals(1,line.zeroBasedLine());assertEquals(FindingNavigation.Match.ORIGINAL,line.match());
    }
    @Test void uniqueMovedMultilineEvidenceUsesCurrentDocumentLineNumbers(){
        var line=FindingNavigation.currentLine("// unsaved insertion\n// another insertion\nvoid check() {\n  throw new Error();\n}\n","void check() {\r\n  throw new Error();\r\n}",1);
        assertEquals(2,line.zeroBasedLine());assertEquals(FindingNavigation.Match.MOVED,line.match());assertTrue(line.message().contains("唯一"));
    }
    @Test void ambiguousMovedEvidenceWarnsAndKeepsOriginalLineVicinity(){
        var line=FindingNavigation.currentLine("header\nchanged\nlog(order);\nfooter\nlog(order);","log(order);",2);
        assertEquals(1,line.zeroBasedLine());assertEquals(FindingNavigation.Match.APPROXIMATE,line.match());assertTrue(line.message().contains("多处"));
    }
    @Test void modifiedOrDeletedEvidenceClampsToAvailableLinesAndDoesNotClaimExactMatch(){
        var line=FindingNavigation.currentLine("one\ntwo","gone();",50);
        assertEquals(1,line.zeroBasedLine());assertEquals(FindingNavigation.Match.APPROXIMATE,line.match());assertTrue(line.message().contains("已修改或删除"));assertTrue(line.message().contains("仅作定位参考"));
    }
    @Test void currentChineseDocumentTextIsUsedWithoutReopeningFileWithUtf8()throws Exception{
        Path source=directory.resolve("Service.java");Files.write(source,"// 磁盘旧内容".getBytes(java.nio.charset.Charset.forName("GBK")));
        String ideDocument="// 未保存的说明\n  logger.info(\"中文订单\");\n";
        var line=FindingNavigation.currentLine(ideDocument,"  logger.info(\"中文订单\");",1);
        assertEquals(1,line.zeroBasedLine());assertEquals(FindingNavigation.Match.MOVED,line.match());
    }
}
