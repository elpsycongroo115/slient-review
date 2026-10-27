package dev.silentreview.idea;

import dev.silentreview.core.Rules;
import java.util.Objects;

/** Credential operations are called by ReviewSettings only from worker threads. */
final class ApiKeyStorage {
    static final String PENDING_LABEL="新 API Key 尚未保存，请点击“应用”或“确定”。";
    interface Store {
        String read(String endpoint);
        void write(String endpoint,String value);
        boolean memoryOnly();
    }
    record Status(boolean present,boolean memoryOnly) {
        String label() {
            if(!present)return "当前接口尚未保存 API Key";
            return memoryOnly ? "API Key 已保存（仅本次 IDEA 会话；重启后需重填）" : "API Key 已保存（安全存储，不回显）";
        }
    }
    static String serviceName(String projectHash,String endpoint) {
        // Keep the existing key exactly: changing URL normalization would orphan saved keys.
        return "SilentReview:"+projectHash+":"+Rules.sha256(endpoint.strip()).substring(0,16);
    }
    static Status inspect(Store store,String endpoint) {
        String value=store.read(endpoint);
        return new Status(value!=null&&!value.isEmpty(),store.memoryOnly());
    }
    static Status saveVerified(Store store,String endpoint,String value) {
        validateReplacement(value);
        if(value.isEmpty())return inspect(store,endpoint);
        try {
            store.write(endpoint,value);
            if(!Objects.equals(value,store.read(endpoint)))throw new IllegalStateException();
            return new Status(true,store.memoryOnly());
        }catch(RuntimeException failure) {
            // Provider exceptions can contain credential details; never echo them in UI or logs.
            throw new IllegalStateException("API Key 保存失败或无法回读确认。输入已保留，请检查 IDEA 的密码存储设置后重试。");
        }
    }
    static void validateReplacement(CharSequence value){
        if(value.length()>0&&value.chars().allMatch(Character::isWhitespace))
            throw new IllegalArgumentException("API Key 不能全部为空白。输入已保留；如需保留原 Key，请将输入框留空。");
    }
    /** Each new lookup invalidates previous callbacks, including a lookup for the same URL. */
    static final class Requests {
        private long current;
        synchronized long next(){return ++current;}
        synchronized boolean isCurrent(long request){return request==current;}
        synchronized boolean canDisplay(long request,boolean hasNewInput){return request==current&&!hasNewInput;}
    }
    private ApiKeyStorage(){}
}
