package dev.silentreview.idea;

import dev.silentreview.core.Rules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Creates an editable starter file explicitly; existing project rules are never replaced. */
final class RuleTemplate {
    record Created(Path path,boolean created){}
    static Path resolve(String basePath,String configuredPath) {
        if(configuredPath==null||configuredPath.isBlank())throw new IllegalArgumentException("请先填写规则集路径。");
        Path path=Path.of(configuredPath.strip());
        if(!path.isAbsolute()) {
            if(basePath==null||basePath.isBlank())throw new IllegalArgumentException("项目没有本地根目录，请填写规则文件的绝对路径。");
            path=Path.of(basePath).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
    static String content() throws IOException {
        try(var in=RuleTemplate.class.getResourceAsStream("/review/default-rules.json")) {
            if(in==null)throw new IOException("缺少内置规则模板，请重新安装插件。");
            String text=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            Rules.parse(text);
            return text;
        }
    }
    static Created create(Path path) throws IOException {
        if(Files.exists(path))return new Created(path,false);
        String text=content();
        if(path.getParent()!=null)Files.createDirectories(path.getParent());
        try {Files.writeString(path,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
        catch(FileAlreadyExistsException exists){return new Created(path,false);}
        return new Created(path,true);
    }
    private RuleTemplate(){}
}
