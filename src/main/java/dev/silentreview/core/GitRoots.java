package dev.silentreview.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded repository discovery. Only directory names and .git marker metadata are inspected. */
public final class GitRoots {
    public record Limits(int depth,int directories){
        public Limits{if(depth<0||directories<1)throw new IllegalArgumentException("仓库发现上限无效");}
    }
    public record Discovery(List<Path> roots,List<String> notes,List<String> limitations,boolean truncated,int visitedDirectories){
        public Discovery{roots=List.copyOf(roots);notes=List.copyOf(notes);limitations=List.copyOf(limitations);}
    }
    public static final Limits DEFAULT_LIMITS=new Limits(6,5000);
    private static final Set<String> SKIPPED=Set.of(".git",".idea","node_modules","build","target","out",".gradle",".tools",
        ".venv","venv","dist","coverage",".next",".nuxt",".cache",".pytest_cache",".mypy_cache",".svn",".hg");
    private record Directory(Path path,int depth){}

    public static Discovery discover(Collection<Path> registered,Collection<Path> hints,BooleanSupplier canceled){
        return discover(registered,hints,DEFAULT_LIMITS,canceled);
    }
    public static Discovery discover(Collection<Path> registered,Collection<Path> hints,Limits limits,BooleanSupplier canceled){
        Objects.requireNonNull(limits);Objects.requireNonNull(canceled);
        checkCanceled(canceled);
        LinkedHashSet<Path> roots=new LinkedHashSet<>(),scheduled=new LinkedHashSet<>();
        LinkedHashSet<String> warnings=new LinkedHashSet<>();List<Directory> frontier=new ArrayList<>();
        boolean[] truncated={false};
        for(Path path:registered){
            checkCanceled(canceled);Path real=canonical(path,warnings,truncated);
            if(real!=null){
                roots.add(real);enqueue(real,0,frontier,scheduled);
                if(!hasMarker(real)){truncated[0]=true;warnings.add("IDE 已登记的目录没有可识别的 .git 标记，将在范围准备时单独验证："+real);}
            }else if(path!=null&&!hasSymbolicSegment(path)){
                // Keep invalid IDE registrations visible as failed roots instead of silently dropping them.
                roots.add(path.toAbsolutePath().normalize());
            }
        }
        for(Path path:hints){
            checkCanceled(canceled);Path real=canonical(path,warnings,truncated);
            if(real!=null){
                // A project/content root may be a subdirectory of a Git root. Inspect only
                // ancestor marker metadata: never recursively scan the ancestor's other trees.
                Path enclosing=nearestRoot(real,canceled);
                if(enclosing!=null)roots.add(enclosing);
                enqueue(real,0,frontier,scheduled);
            }
        }
        // Explicit hints are all inspected before the shared descendant budget. At each
        // breadth level, consume one child per parent in turn so a large first root cannot
        // fill the budget before a later content root's immediate repositories are checked.
        boolean stop=false;
        if(frontier.size()>limits.directories()){
            truncated[0]=true;warnings.add(directoryLimit(limits));
            frontier=new ArrayList<>(frontier.subList(0,limits.directories()));
        }
        while(!frontier.isEmpty()&&!stop){
            List<DirectoryCursor> cursors=frontier.stream().map(DirectoryCursor::new).toList();
            List<DirectoryCursor> active=new ArrayList<>(cursors);List<Directory> next=new ArrayList<>();
            try{
                while(!active.isEmpty()&&!stop){
                    for(Iterator<DirectoryCursor> iterator=active.iterator();iterator.hasNext();){
                        checkCanceled(canceled);DirectoryCursor cursor=iterator.next();Path child;
                        try{child=cursor.next(canceled,warnings,truncated);}
                        catch(IOException|DirectoryIteratorException|SecurityException failure){
                            truncated[0]=true;warnings.add("目录无法扫描，自动发现可能遗漏仓库："+cursor.directory.path());child=null;
                        }
                        if(child==null){cursor.close();iterator.remove();continue;}
                        if(scheduled.contains(child))continue;
                        if(cursor.directory.depth()>=limits.depth()){
                            truncated[0]=true;warnings.add("仓库自动发现达到 "+limits.depth()+" 层深度上限，存在更深目录未扫描；请将遗漏仓库登记为 IDE Git 根后重新准备范围。");
                            cursor.close();iterator.remove();continue;
                        }
                        if(scheduled.size()>=limits.directories()){
                            truncated[0]=true;warnings.add(directoryLimit(limits));stop=true;break;
                        }
                        Path real=canonical(child,warnings,truncated);
                        if(real!=null&&scheduled.add(real)){
                            if(hasMarker(real))roots.add(real);
                            next.add(new Directory(real,cursor.directory.depth()+1));
                        }
                    }
                }
            }finally{cursors.forEach(DirectoryCursor::close);}
            frontier=next;
        }
        List<Path> sorted=roots.stream().sorted().toList();
        List<String> notes=List.of("仓库发现边界：优先识别所有 IDE 已登记 Git 根、项目根及内容根；对子目录提示仅向上检查最近 .git 标记，不递归扫描父目录。向下按层轮转扫描最多 "+limits.depth()+" 层、"+limits.directories()+
            " 个目录；跳过符号链接和 .git/.idea/node_modules/build/target/out/.gradle/.tools/.venv 等产物目录，不保证发现边界外仓库。可在 IDE 的版本控制目录映射中补充 Git 根。");
        if(sorted.isEmpty())warnings.add("扫描范围内未发现可用的 Git 仓库；这不代表项目没有仓库或审查没有问题。");
        return new Discovery(sorted,notes,new ArrayList<>(warnings),truncated[0],Math.min(scheduled.size(),limits.directories()));
    }
    private static void enqueue(Path real,int depth,List<Directory> pending,Set<Path> scheduled){
        if(scheduled.add(real))pending.add(new Directory(real,depth));
    }
    private static String directoryLimit(Limits limits){return "仓库自动发现达到 "+limits.directories()+" 个目录上限，仍有目录未扫描；请将遗漏仓库登记为 IDE Git 根后重新准备范围。";}
    private static Path nearestRoot(Path hint,BooleanSupplier canceled){
        for(Path directory=hint;directory!=null;directory=directory.getParent()){
            checkCanceled(canceled);if(hasMarker(directory))return directory;
        }
        return null;
    }
    private static final class DirectoryCursor {
        private final Directory directory;
        private DirectoryStream<Path> stream;
        private Iterator<Path> entries;
        private DirectoryCursor(Directory directory){this.directory=directory;}
        private Path next(BooleanSupplier canceled,Set<String> warnings,boolean[] truncated)throws IOException{
            if(stream==null){stream=Files.newDirectoryStream(directory.path());entries=stream.iterator();}
            while(entries.hasNext()){
                checkCanceled(canceled);Path entry=entries.next();
                if(SKIPPED.contains(entry.getFileName().toString().toLowerCase(Locale.ROOT))||Files.isSymbolicLink(entry))continue;
                BasicFileAttributes attributes;
                try{attributes=Files.readAttributes(entry,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
                catch(IOException|SecurityException failure){truncated[0]=true;warnings.add("部分目录项无法读取，自动发现可能遗漏仓库："+entry);continue;}
                if(attributes.isDirectory()&&!attributes.isSymbolicLink())return entry;
            }
            return null;
        }
        private void close(){if(stream!=null)try{stream.close();}catch(IOException ignored){}}
    }
    private static boolean hasMarker(Path root){
        Path marker=root.resolve(".git");
        return !Files.isSymbolicLink(marker)&&(Files.isDirectory(marker,LinkOption.NOFOLLOW_LINKS)||Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS));
    }
    private static Path canonical(Path path,Set<String> warnings,boolean[] truncated){
        if(path==null)return null;
        if(hasSymbolicSegment(path)){
            truncated[0]=true;warnings.add("已跳过符号链接目录，自动发现不包含其目标："+path.toAbsolutePath().normalize());return null;
        }
        try{
            Path real=path.toRealPath();
            if(!Files.isDirectory(real,LinkOption.NOFOLLOW_LINKS))throw new IOException();
            return real;
        }catch(IOException|SecurityException failure){
            truncated[0]=true;warnings.add("目录不存在或无法访问，自动发现可能遗漏仓库："+path.toAbsolutePath().normalize());return null;
        }
    }
    private static boolean hasSymbolicSegment(Path path){
        Path absolute=path.toAbsolutePath(),part=absolute.getRoot();
        for(Path segment:absolute){part=part.resolve(segment);if(Files.isSymbolicLink(part))return true;}
        return false;
    }
    private static void checkCanceled(BooleanSupplier canceled){if(canceled.getAsBoolean())throw new CancellationException("仓库发现已取消");}
    private GitRoots(){}
}
