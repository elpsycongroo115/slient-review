package dev.silentreview.idea;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.components.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

@State(name="SilentReviewSettings", storages=@Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ReviewSettings implements PersistentStateComponent<ReviewSettings.Data> {
    public static final class Data {
        public String rulesPath=".silent-review/rules.json";
        public String endpoint="https://api.deepseek.com";
        public String model="deepseek-flash";
        public boolean automatic=false;
        public boolean allowExternal=false;
        public boolean fishVisible=true;
        public int fishX=-1, fishY=-1;
    }
    private volatile Data data=new Data();
    private final Project project;
    public ReviewSettings(Project project) { this.project=project; }
    public static ReviewSettings get(Project project) { return project.getService(ReviewSettings.class); }
    @Override public @NotNull Data getState() { return data; }
    @Override public void loadState(@NotNull Data state) { data=state; }
    public Data data() { return data; }
    public String key(String endpoint) {
        requireBackgroundThread();
        try {
            String value=PasswordSafe.getInstance().getPassword(attributes(endpoint));
            return value==null ? "" : value;
        }catch(RuntimeException failure){throw new IllegalStateException("无法读取 API Key，请检查 IDEA 的密码存储设置。");}
    }
    public void setKey(String endpoint,String key) {
        requireBackgroundThread();
        PasswordSafe.getInstance().setPassword(attributes(endpoint),key);
    }
    ApiKeyStorage.Status keyStatus(String endpoint){
        requireBackgroundThread();
        return ApiKeyStorage.inspect(keyStore(),endpoint);
    }
    ApiKeyStorage.Status saveKeyVerified(String endpoint,String key){
        requireBackgroundThread();
        return ApiKeyStorage.saveVerified(keyStore(),endpoint,key);
    }
    private static void requireBackgroundThread(){
        if(ApplicationManager.getApplication().isDispatchThread())
            throw new IllegalStateException("API Key 存储操作必须在后台线程执行。");
    }
    private ApiKeyStorage.Store keyStore(){return new ApiKeyStorage.Store(){
        public String read(String endpoint){return key(endpoint);}
        public void write(String endpoint,String value){setKey(endpoint,value);}
        public boolean memoryOnly(){return PasswordSafe.getInstance().isMemoryOnly();}
    };}
    private CredentialAttributes attributes(String endpoint) {
        return new CredentialAttributes(ApiKeyStorage.serviceName(project.getLocationHash(),endpoint));
    }
}
