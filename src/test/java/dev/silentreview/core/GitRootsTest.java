package dev.silentreview.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitRootsTest {
    @TempDir Path root;
    private String git(Path directory,String...arguments)throws Exception{
        List<String> command=new ArrayList<>(List.of("git","-C",directory.toString(),"-c","core.hooksPath="+root.resolve("no-hooks"),
            "-c","commit.gpgsign=false","-c","user.name=SilentReview Tests","-c","user.email=test@example.invalid"));
        command.addAll(List.of(arguments));ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL",root.resolve("no-global-config").toString());
        Process process=builder.start();String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),text);return text.strip();
    }
    private Path repository(String path)throws Exception{
        Path directory=Files.createDirectories(root.resolve(path));git(directory,"init","-b","test/discovery");return directory.toRealPath();
    }
    @Test void findsSiblingRepositoriesEvenWhenOnlyOneWasRegistered()throws Exception{
        Path first=repository("workspace/java-service"),second=repository("workspace/web-ui"),third=repository("outside-content-root");
        var discovery=GitRoots.discover(List.of(first),List.of(root.resolve("workspace"),third),()->false);
        assertEquals(Set.of(first,second,third),Set.copyOf(discovery.roots()));assertFalse(discovery.truncated());
        assertTrue(discovery.notes().get(0).contains("不保证发现边界外仓库"));assertTrue(discovery.limitations().isEmpty());
    }
    @Test void continuesInsideKnownRepositoriesToFindNestedRootsAndDeduplicatesHints()throws Exception{
        Path outer=repository("workspace"),nested=repository("workspace/components/plugin"),sibling=repository("tools");
        var discovery=GitRoots.discover(List.of(outer,outer.resolve(".")),List.of(outer,root,nested,sibling),()->false);
        assertEquals(Set.of(outer,nested,sibling),Set.copyOf(discovery.roots()));assertEquals(3,discovery.roots().size());
        assertFalse(discovery.truncated());
    }
    @Test void discoversARealWorktreeGitFileWithoutReadingItsContents()throws Exception{
        Path main=repository("workspace/main");git(main,"commit","--allow-empty","-m","discovery fixture");
        Path worktree=root.resolve("workspace/linked-worktree");git(main,"worktree","add","--detach",worktree.toString());
        assertTrue(Files.isRegularFile(worktree.resolve(".git")));
        var discovery=GitRoots.discover(List.of(main),List.of(root.resolve("workspace")),()->false);
        assertEquals(Set.of(main,worktree.toRealPath()),Set.copyOf(discovery.roots()));
    }
    @Test void aSubdirectoryHintFindsOnlyItsClosestEnclosingRootWithoutScanningAncestorSiblings()throws Exception{
        Path outer=repository("workspace/outer"),nested=repository("workspace/outer/module");
        Path hint=Files.createDirectories(nested.resolve("component"));
        Path outsideHint=repository("workspace/outer/module/private-sibling");
        var discovery=GitRoots.discover(List.of(),List.of(hint),()->false);
        assertEquals(List.of(nested),discovery.roots());
        assertFalse(discovery.roots().contains(outer));assertFalse(discovery.roots().contains(outsideHint));
        assertTrue(discovery.limitations().isEmpty());
        assertTrue(discovery.notes().stream().anyMatch(t->t.contains("不递归扫描父目录")));
    }
    @Test void allExplicitRootsAreRecognizedBeforeTheSharedDescendantBudget()throws Exception{
        Path huge=repository("huge"),later=repository("later"),last=repository("last");
        for(int i=0;i<15;i++)Files.createDirectories(huge.resolve("child-"+i));
        var discovery=GitRoots.discover(List.of(huge),List.of(huge,later,last),new GitRoots.Limits(3,1),()->false);
        assertEquals(Set.of(huge,later,last),Set.copyOf(discovery.roots()));
        assertTrue(discovery.truncated());assertTrue(discovery.visitedDirectories()<=1);
    }
    @Test void breadthLevelRoundRobinFindsALaterHintsImmediateRepositoryBeforeAHugeFirstTree()throws Exception{
        Path huge=repository("projects/hdpos"),laterContainer=Files.createDirectories(root.resolve("projects/latin"));
        for(int i=0;i<30;i++)Files.createDirectories(huge.resolve("module-"+i).resolve("src/deep"));
        Path laterRepository=repository("projects/latin/latin-core");
        var discovery=GitRoots.discover(List.of(huge),List.of(huge,laterContainer),new GitRoots.Limits(6,4),()->false);
        assertEquals(Set.of(huge,laterRepository),Set.copyOf(discovery.roots()));
        assertTrue(discovery.truncated());assertTrue(discovery.limitations().stream().anyMatch(t->t.contains("目录上限")));
    }
    @Test void skipsGeneratedDirectoriesAndGitMetadata()throws Exception{
        Path real=repository("workspace/source");
        for(String ignored:List.of(".git",".idea","node_modules","build","target","out",".gradle",".tools",".venv","dist")){
            Path hidden=root.resolve("workspace").resolve(ignored).resolve("embedded");
            Files.createDirectories(hidden.resolve(".git"));
        }
        var discovery=GitRoots.discover(List.of(),List.of(root.resolve("workspace")),()->false);
        // The project .git marker itself makes workspace a candidate; its internals must not be traversed.
        assertEquals(Set.of(real,root.resolve("workspace").toRealPath()),Set.copyOf(discovery.roots()));
        assertTrue(discovery.roots().stream().noneMatch(p->p.endsWith("embedded")));
    }
    @Test void skipsSymbolicLinksAndDoesNotFollowThemOutsideTheWorkspace()throws Exception{
        Path actual=repository("outside/real-repository"),workspace=Files.createDirectories(root.resolve("workspace"));
        Path link=workspace.resolve("linked-repository");
        try{Files.createSymbolicLink(link,actual);}catch(IOException|UnsupportedOperationException|SecurityException unavailable){assumeTrue(false,"This host cannot create directory symlinks");return;}
        var discovery=GitRoots.discover(List.of(),List.of(workspace),()->false);
        assertTrue(discovery.roots().isEmpty());
        var hinted=GitRoots.discover(List.of(),List.of(link),()->false);
        assertTrue(hinted.roots().isEmpty());assertTrue(hinted.truncated());
        assertTrue(hinted.limitations().stream().anyMatch(t->t.contains("符号链接")));
    }
    @Test void reportsDepthAndDirectoryBoundsInsteadOfClaimingCompleteCoverage()throws Exception{
        repository("workspace/first/second/deep-repository");Path workspace=root.resolve("workspace");
        var shallow=GitRoots.discover(List.of(),List.of(workspace),new GitRoots.Limits(1,100),()->false);
        assertTrue(shallow.roots().isEmpty());assertTrue(shallow.truncated());
        assertTrue(shallow.limitations().stream().anyMatch(t->t.contains("深度上限")));
        var bounded=GitRoots.discover(List.of(),List.of(workspace),new GitRoots.Limits(9,2),()->false);
        assertTrue(bounded.visitedDirectories()<=2);assertTrue(bounded.truncated());
        assertTrue(bounded.limitations().stream().anyMatch(t->t.contains("目录上限")));
    }
    @Test void registeredRootsRemainVisibleEvenWhenTheirDirectoriesAreMissing()throws Exception{
        Path missing=root.resolve("missing-registered-repository");
        var discovery=GitRoots.discover(List.of(missing),List.of(),()->false);
        assertEquals(List.of(missing.toAbsolutePath().normalize()),discovery.roots());assertTrue(discovery.truncated());
        assertTrue(discovery.limitations().stream().anyMatch(t->t.contains("不存在或无法访问")));
    }
    @Test void cancellationCannotTurnIntoAnEmptySuccessfulDiscovery(){
        assertThrows(CancellationException.class,()->GitRoots.discover(List.of(),List.of(root),()->true));
    }
}
